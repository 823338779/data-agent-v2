package com.datagent.agentv2.artifact.domain;

/** 文档构建任务从准备到验证完成的持久化生命周期。 */
public enum ArtifactBuildStatus {
    PREPARED,
    RUNNING,
    RENDERING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    /** 终态任务不得再次执行或发布副作用。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
