package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.react.ReactRecoveryPlanner;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.annotation.PreDestroy;
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
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Future<?>> active = new ConcurrentHashMap<>();

    @Override
    public void start(ClaimedAgentExecution run, Runnable onTerminated) {
        Future<?> future =
                workers.submit(
                        () -> {
                            try {
                                TenantContext.run(
                                        run.tenantId(),
                                        () -> executor.execute(run, recovery.recover(run)));
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
