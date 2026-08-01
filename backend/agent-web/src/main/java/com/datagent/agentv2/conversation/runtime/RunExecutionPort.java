package com.datagent.agentv2.conversation.runtime;

public interface RunExecutionPort {

    void start(OwnedRun run, Runnable onTerminated);

    default void cancel(OwnedRun run) {}

    record OwnedRun(String tenantId, String runId, String ownerInstanceId, long executionAttempt) {}
}
