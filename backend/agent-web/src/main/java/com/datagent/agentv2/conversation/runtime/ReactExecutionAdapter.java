package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.context.ContextPreparationException;
import com.datagent.agentv2.conversation.enums.ExecutionFailureCode;
import com.datagent.agentv2.conversation.exception.ExecutionDeadlineExceededException;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.react.ReactRecoveryPlanner;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class ReactExecutionAdapter implements AgentExecutionPort {

    private final ReactExecutionEngine executor;
    private final ReactRecoveryPlanner recovery;
    private final AgentExecutionProcessTerminationService termination;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Future<?>> active = new ConcurrentHashMap<>();

    @Override
    public void start(ClaimedAgentExecution run, Runnable onTerminated) {
        Future<?> future =
                workers.submit(
                        () -> {
                            try {
                                executor.execute(run, recovery.recover(run));
                            } catch (ExecutionOwnershipLostException ignored) {
                                // 接管后的旧 worker 必须静默退出，不能修改新 owner 的中心消息或执行终态。
                            } catch (ExecutionDeadlineExceededException ignored) {
                                // 租约服务已经在抛出异常前原子提交了超时终态及过程节点收敛。
                            } catch (ContextPreparationException ignored) {
                                // 上下文策略已经通过 execution control 端口提交稳定失败终态。
                            } catch (RuntimeException failure) {
                                termination.failOwned(
                                        run,
                                        ExecutionFailureCode.INTERNAL_ERROR.value(),
                                        failure.getMessage(),
                                        Instant.now());
                            } finally {
                                active.remove(run.executionId());
                                onTerminated.run();
                            }
                        });
        active.put(run.executionId(), future);
    }

    @Override
    public void cancel(ClaimedAgentExecution run) {
        Future<?> future = active.remove(run.executionId());
        if (future != null) {
            future.cancel(true);
        }
    }

    @PreDestroy
    public void close() {
        active.values().forEach(task -> task.cancel(true));
        active.clear();
        workers.shutdownNow();
    }
}
