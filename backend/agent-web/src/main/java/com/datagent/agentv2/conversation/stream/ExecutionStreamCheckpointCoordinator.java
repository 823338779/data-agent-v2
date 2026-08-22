package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 协调当前执行所有者的本地输出累计与数据库检查点写入。
 *
 * <p>该类型负责运行时 fencing 和检查点时机，不承担通用持久化 CRUD；数据库访问由 {@code AgentExecutionStreamCheckpointService} 提供。
 */
@Service
@RequiredArgsConstructor
public final class ExecutionStreamCheckpointCoordinator {

    /** 提供带 owner fencing 和乐观锁的 checkpoint 数据库更新。 */
    private final AgentExecutionStreamCheckpointService streamCheckpoints;

    /** 查询执行与会话关联，并校验执行是否仍然存在。 */
    private final AgentExecutionService agentExecutions;

    /** 按 execution/attempt/model invocation 隔离当前实例的回答累计器。 */
    private final ConcurrentMap<ExecutionStreamKey, ExecutionStreamAccumulator> accumulators =
            new ConcurrentHashMap<>();

    /** 缓存 execution 到 conversation session 的稳定映射，避免每个增量查询执行表。 */
    private final ConcurrentMap<String, String> conversationSessionIds = new ConcurrentHashMap<>();

    /** 创建 checkpoint 协调器并注入权威持久化服务。 */
    /**
     * 切换模型调用时持久化空投影，并清理该执行的旧本地累计。
     *
     * @param run 当前 owner 已领取的执行
     * @param modelInvocationSequence 新模型调用序号
     * @param now 统一写入时间
     */
    public void beginModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now) {
        // 先基于最新乐观锁版本重置数据库投影，失败即表示 owner 或版本已经变化。
        AgentExecutionStreamCheckpoint current = requireStream(run.executionId());
        // begin 更新通过 version、attempt 和 owner 条件拒绝旧 worker 切换投影。
        if (!streamCheckpoints.beginModelInvocation(
                run.executionId(),
                current.getVersion(),
                run.executionAttemptNumber(),
                modelInvocationSequence,
                run.ownerInstanceId(),
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }

        // 数据库切换成功后清理旧 attempt/调用的内存状态，防止迟到回调继续累计。
        accumulators.keySet().removeIf(key -> key.executionId().equals(run.executionId()));

        // 会话标识在模型调用生命周期内稳定，可安全用于后续通知封装。
        conversationSessionIds.put(
                run.executionId(), requireExecution(run.executionId()).getConversationSessionId());

        // 再次读取重置后的权威投影，确保本地版本、序号和阶段与数据库一致。
        AgentExecutionStreamCheckpoint reset = requireStream(run.executionId());
        accumulators.put(key(run, modelInvocationSequence), from(reset, now));
    }

    /**
     * 为回答增量分配序号，并在达到阈值时同步写入完整累计投影。
     *
     * @param run 当前 owner 已领取的执行
     * @param modelInvocationSequence 当前模型调用序号
     * @param answerDelta 非空回答增量
     * @param now 增量发生时间
     * @return 可发送给本地及 Redis 订阅者的回答增量
     */
    public AnswerDelta appendAnswer(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            String answerDelta,
            Instant now) {
        Objects.requireNonNull(answerDelta, "answerDelta");
        ExecutionStreamAccumulator accumulator = accumulator(run, modelInvocationSequence, now);

        // 同一投影的序号分配、累计和阈值写入必须串行，避免并发回调产生乱序。
        synchronized (accumulator) {
            long outputDeltaSequence = accumulator.appendAnswer(answerDelta);

            // checkpoint 保存完整回答，不影响当前增量立即发布给 SSE。
            if (accumulator.shouldCheckpoint(now)) {
                persist(run, accumulator, now);
            }
            return new AnswerDelta(
                    run.tenantId(),
                    conversationSessionId(run.executionId()),
                    run.executionId(),
                    run.executionAttemptNumber(),
                    modelInvocationSequence,
                    outputDeltaSequence,
                    answerDelta,
                    now);
        }
    }

    /**
     * 在模型调用边界收敛短增量，但保留当前执行的本地累计。
     *
     * @param run 当前 owner 已领取的执行
     * @param modelInvocationSequence 当前模型调用序号
     * @param now 边界发生时间
     */
    public void flush(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now) {
        flush(run, modelInvocationSequence, now, false);
    }

    /**
     * 在终态或 lease release 边界收敛短增量，并释放本地累计。
     *
     * @param run 当前 owner 已领取的执行
     * @param modelInvocationSequence 当前模型调用序号
     * @param now 边界发生时间
     */
    public void flushAndRelease(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now) {
        flush(run, modelInvocationSequence, now, true);
    }

    /** 执行实际 flush，并根据调用方语义决定是否移除本地状态。 */
    private void flush(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now,
            boolean release) {
        ExecutionStreamAccumulator accumulator = accumulator(run, modelInvocationSequence, now);

        // flush 与 append 共享同一把累计器锁，保证完整投影覆盖连续序号。
        synchronized (accumulator) {
            // 没有待写字符时只执行可能的本地释放，不产生数据库空更新。
            if (accumulator.hasPending()) {
                persist(run, accumulator, now);
            }

            // 只有 owner 生命周期结束时清理缓存；模型调用边界仍允许后续检查。
            if (release) {
                accumulators.remove(key(run, modelInvocationSequence), accumulator);
                conversationSessionIds.remove(run.executionId());
            }
        }
    }

    /**
     * owner 释放 lease 前收敛并移除该 attempt 的全部本地累计器。
     *
     * @param run 即将释放的执行
     * @param now lease release 时间
     */
    public void flushExecutionAndRelease(
            AgentExecutionPort.ClaimedAgentExecution run, Instant now) {
        // 先复制匹配键，避免遍历过程中删除 ConcurrentMap 元素影响本次集合。
        accumulators.keySet().stream()
                .filter(
                        key ->
                                key.executionId().equals(run.executionId())
                                        && key.executionAttemptNumber()
                                                == run.executionAttemptNumber())
                .toList()
                .forEach(key -> flushAndRelease(run, key.modelInvocationSequence(), now));
    }

    /** 获取当前投影累计器；缓存缺失时从数据库权威 checkpoint 恢复。 */
    private ExecutionStreamAccumulator accumulator(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now) {
        ExecutionStreamKey key = key(run, modelInvocationSequence);

        // 只有 execution、attempt 和模型调用序号全部匹配时才允许恢复本地累计。
        return accumulators.computeIfAbsent(
                key,
                ignored -> {
                    AgentExecutionStreamCheckpoint stream = requireStream(run.executionId());
                    // 数据库投影必须与请求的 attempt 和模型调用完全一致。
                    if (!Objects.equals(
                                    stream.getExecutionAttemptNumber(),
                                    run.executionAttemptNumber())
                            || !Objects.equals(
                                    stream.getModelInvocationSequence(), modelInvocationSequence)) {
                        throw new ExecutionOwnershipLostException(run.executionId());
                    }
                    return from(stream, now);
                });
    }

    /** 使用最新数据库版本和 owner fencing 条件写入完整回答投影。 */
    private void persist(
            AgentExecutionPort.ClaimedAgentExecution run,
            ExecutionStreamAccumulator accumulator,
            Instant now) {
        // 每次写入前读取最新版本，避免本地缓存长期持有已经失效的乐观锁值。
        AgentExecutionStreamCheckpoint current = requireStream(run.executionId());
        // checkpoint 更新以 version、attempt、model invocation 和 owner 共同 fencing。
        if (!streamCheckpoints.checkpoint(
                run.executionId(),
                current.getVersion(),
                accumulator.executionAttemptNumber(),
                accumulator.modelInvocationSequence(),
                run.ownerInstanceId(),
                accumulator.outputDeltaSequence(),
                accumulator.answer(),
                accumulator.phase().value(),
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }

        // 仅在数据库确认成功后重置本地阈值计量，失败时保留待写状态供异常处理。
        accumulator.checkpointed(now);
    }

    /** 查找执行的权威流 checkpoint，不存在时按所有权失效处理。 */
    private AgentExecutionStreamCheckpoint requireStream(String executionId) {
        AgentExecutionStreamCheckpoint stream = streamCheckpoints.findByExecutionId(executionId);

        // checkpoint 与执行一一对应，缺失表示执行不再满足当前写入前提。
        if (stream == null) {
            throw new ExecutionOwnershipLostException(executionId);
        }
        return stream;
    }

    /** 获取通知所需会话标识，并在缓存缺失时通过执行记录回填。 */
    private String conversationSessionId(String executionId) {
        return conversationSessionIds.computeIfAbsent(
                executionId, ignored -> requireExecution(executionId).getConversationSessionId());
    }

    /** 查找执行记录，不存在时统一转换为所有权丢失异常。 */
    private AgentExecution requireExecution(String executionId) {
        AgentExecution run = agentExecutions.findByExecutionId(executionId);

        // 增量只能关联仍存在的执行，禁止生成无法路由到会话的通知。
        if (run == null) {
            throw new ExecutionOwnershipLostException(executionId);
        }
        return run;
    }

    /** 将数据库投影转换为可继续累计的本地对象。 */
    private static ExecutionStreamAccumulator from(
            AgentExecutionStreamCheckpoint stream, Instant fallback) {
        // 初始 checkpoint 可能没有落库时间，使用本次恢复时间启动时间阈值窗口。
        Instant checkpointedAt =
                stream.getCheckpointedAt() == null ? fallback : stream.getCheckpointedAt();
        return new ExecutionStreamAccumulator(
                stream.getExecutionAttemptNumber(),
                stream.getModelInvocationSequence(),
                stream.getOutputDeltaSequence(),
                stream.getAnswer(),
                stream.getPhase(),
                checkpointedAt);
    }

    /** 根据 execution、attempt 和模型调用序号构造本地累计器唯一键。 */
    private static ExecutionStreamKey key(
            AgentExecutionPort.ClaimedAgentExecution run, long modelInvocationSequence) {
        return new ExecutionStreamKey(
                run.executionId(), run.executionAttemptNumber(), modelInvocationSequence);
    }

    /**
     * 已分配序号、可跨组件传播的单个回答增量。
     *
     * @param tenantId 执行所属租户
     * @param conversationSessionId 增量所属会话
     * @param executionId 增量所属执行
     * @param executionAttemptNumber owner fencing attempt
     * @param modelInvocationSequence 模型调用序号
     * @param outputDeltaSequence 调用内回答增量序号
     * @param answerDelta 回答正文片段
     * @param occurredAt 增量发生时间
     */
    public record AnswerDelta(
            String tenantId,
            String conversationSessionId,
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String answerDelta,
            Instant occurredAt) {}
}
