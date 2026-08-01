package com.datagent.agentv2.conversation.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public final class NoopRunExecutionAdapter implements RunExecutionPort {

    private final ConcurrentMap<String, Runnable> terminations = new ConcurrentHashMap<>();

    @Override
    public void start(OwnedRun run, Runnable onTerminated) {
        terminations.put(run.runId(), onTerminated);
    }

    @Override
    public void cancel(OwnedRun run) {
        Runnable onTerminated = terminations.remove(run.runId());
        if (onTerminated != null) {
            onTerminated.run();
        }
    }
}
