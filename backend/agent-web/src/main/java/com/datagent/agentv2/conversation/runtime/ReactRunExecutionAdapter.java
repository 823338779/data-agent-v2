package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.react.ReactRecoveryPlanner;
import com.datagent.agentv2.conversation.react.ReactRunExecutor;
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
public final class ReactRunExecutionAdapter implements RunExecutionPort {

    private final ReactRunExecutor executor;
    private final ReactRecoveryPlanner recovery;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Future<?>> active = new ConcurrentHashMap<>();

    @Override
    public void start(OwnedRun run, Runnable onTerminated) {
        Future<?> future =
                workers.submit(
                        () -> {
                            try {
                                TenantContext.run(
                                        run.tenantId(),
                                        () -> executor.execute(run, recovery.recover(run)));
                            } finally {
                                active.remove(run.runId());
                                onTerminated.run();
                            }
                        });
        active.put(run.runId(), future);
    }

    @Override
    public void cancel(OwnedRun run) {
        Future<?> future = active.remove(run.runId());
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
