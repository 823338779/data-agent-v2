package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 记录当前实例正在执行的任务及容量许可，并在 owner 释放时收敛流状态。 */
@Component
public final class ExecutionOwnerRegistry {

    /** 当前实例持有的执行数量指标名。 */
    private static final String METRIC_OWNER_REGISTRY_SIZE =
            "data_agent.runtime.owner_registry.size";

    /** executionId 到本机 owner/容量许可的并发映射。 */
    private final ConcurrentMap<String, Ownership> owners = new ConcurrentHashMap<>();

    /** 在 owner 释放前强制 checkpoint 尚未收敛的回答增量。 */
    private final ExecutionStreamCheckpointCoordinator checkpoints;

    /** 创建 owner 注册表并注册实例级容量指标。 */
    public ExecutionOwnerRegistry(
            ExecutionWorkerCapacity capacity,
            MeterRegistry meters,
            ExecutionStreamCheckpointCoordinator checkpoints) {
        this.checkpoints = checkpoints;
        meters.gauge(METRIC_OWNER_REGISTRY_SIZE, this, ExecutionOwnerRegistry::size);
    }

    /**
     * 原子注册本机执行权；重复注册时立即归还新取得的容量许可。
     *
     * @param run 当前实例领取的执行
     * @param permit 该执行占用的 worker 容量许可
     * @return 注册成功返回 true，execution 已存在返回 false
     */
    public boolean register(
            AgentExecutionPort.ClaimedAgentExecution run, ExecutionWorkerCapacity.Permit permit) {
        Ownership previous = owners.putIfAbsent(run.executionId(), new Ownership(run, permit));

        // 重复执行不能占用第二份容量，失败路径必须立即关闭调用方传入的许可。
        if (previous != null) {
            permit.close();
            return false;
        }
        return true;
    }

    /**
     * 释放本机执行权，先尽力收敛回答 checkpoint，再归还容量许可。
     *
     * @param executionId 要释放的执行标识
     */
    public void release(String executionId) {
        Ownership ownership = owners.remove(executionId);

        // 只有成功移除的线程负责 flush 和关闭 permit，避免重复释放。
        if (ownership != null) {
            // flush 是尽力而为的清理步骤，任何所有权丢失都不能阻止 permit 释放。
            try {
                checkpoints.flushExecutionAndRelease(ownership.execution(), Instant.now());
            } catch (ExecutionOwnershipLostException ignored) {
                // Lease 已被接管或执行已终态化时，新的 owner/终态投影是权威结果。
            } finally {
                // 无论 checkpoint 是否成功，已经移除的 owner 都必须归还容量。
                ownership.permit().close();
            }
        }
    }

    /** 返回当前实例是否仍登记为指定执行的 owner。 */
    public boolean owns(String executionId) {
        return owners.containsKey(executionId);
    }

    /** 返回当前实例登记的执行数量，供容量指标读取。 */
    public int size() {
        return owners.size();
    }

    /** 返回当前实例全部 owner 执行的不可变快照。 */
    public List<AgentExecutionPort.ClaimedAgentExecution> ownedExecutions() {
        return owners.values().stream().map(Ownership::execution).toList();
    }

    /** 应用关闭时逐个释放本机 owner，确保许可和短增量得到清理。 */
    @PreDestroy
    public void close() {
        // release 会修改 owners，因此基于并发 map 的 key 视图逐项调用幂等释放。
        owners.keySet().forEach(this::release);
    }

    /**
     * 绑定本机正在执行的任务与其占用的容量许可。
     *
     * @param execution 当前实例持有的执行
     * @param permit 执行占用的容量许可
     */
    private record Ownership(
            AgentExecutionPort.ClaimedAgentExecution execution,
            ExecutionWorkerCapacity.Permit permit) {}
}
