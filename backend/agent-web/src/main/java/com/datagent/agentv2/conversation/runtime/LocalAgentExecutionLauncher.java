package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.enums.ExecutionFailureCode;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.diagnostics.SafeFailures;
import com.datagent.agentv2.memory.tool.MemoryInvocationRegistry;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.skill.runtime.ExecutionSkillViews;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把已提交的执行事件转换为当前 JVM 内的虚拟线程任务。
 *
 * <p>监听器必须在事务提交后启动或取消任务，避免后台线程观察到尚未提交的执行记录。这里不承担重试和跨进程恢复；进程重启后的遗留状态由 {@link
 * StaleExecutionFinalizer} 统一收敛。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class LocalAgentExecutionLauncher {

    private final LocalAgentExecutionRegistry registry;
    private final ReactExecutionEngine engine;
    private final AgentExecutionProcessTerminationService termination;
    private final ExecutionToolViews toolViews;
    private final ExecutionSkillViews skillViews;
    private final MemoryInvocationRegistry memoryInvocations;
    private final ExecutionStreamCheckpointCoordinator checkpoints;
    private final ModelInvocationCoordinator modelInvocations;
    private final ConversationProcessCoordinator processes;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void start(LocalExecutionStarted event) {
        LocalAgentExecution execution = event.execution();
        registry.start(execution.executionId(), () -> execute(execution));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cancel(LocalExecutionCancelled event) {
        registry.cancel(event.executionId());
    }

    private void execute(LocalAgentExecution execution) {
        // 虚拟线程不会继承提交请求的 ThreadLocal，进入业务引擎前显式恢复租户边界。
        TenantContext.run(execution.tenantId(), () -> executeInTenant(execution));
    }

    private void executeInTenant(LocalAgentExecution execution) {
        try {
            toolViews.open(execution.executionId());
            skillViews.open(execution.executionId());
            try {
                engine.execute(execution);
            } finally {
                skillViews.close(execution.executionId());
            }
        } catch (ExecutionStoppedException stopped) {
            // 取消与超时共享停止信号；只有仍为 running 的记录才会被终止服务原子地置为失败。
            termination.failRunning(
                    execution,
                    ExecutionFailureCode.DEADLINE_EXCEEDED,
                    stopped.getMessage(),
                    Instant.now());
        } catch (RuntimeException failure) {
            // 未分类异常在进程边界统一收敛，防止数据库永久残留 running 状态。
            log.error(
                    "本机执行失败 executionId={}",
                    execution.executionId(),
                    SafeFailures.sanitized(failure));
            termination.failRunning(
                    execution,
                    ExecutionFailureCode.INTERNAL_ERROR,
                    "Agent execution failed",
                    Instant.now());
        } finally {
            // 内存清理不依赖终态事务成功；特别是原始 thinking 缓冲不得跨执行遗留。
            checkpoints.releaseExecution(execution.executionId());
            modelInvocations.releaseExecution(execution.executionId());
            processes.releaseExecution(execution.executionId());
            // ReAct 每轮负责精确关闭；执行边界兜底清理上下文准备阶段等异常遗留。
            memoryInvocations.closeExecution(execution.executionId());
            toolViews.close(execution.executionId());
        }
    }
}
