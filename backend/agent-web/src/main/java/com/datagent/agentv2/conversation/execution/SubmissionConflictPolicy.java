package com.datagent.agentv2.conversation.execution;

/** Controls how a message submission behaves when the session already has active work. */
public enum SubmissionConflictPolicy {
    REJECT,
    STEER_ACTIVE;

    public static SubmissionConflictPolicy defaulted(SubmissionConflictPolicy policy) {
        return policy == null ? REJECT : policy;
    }
}
