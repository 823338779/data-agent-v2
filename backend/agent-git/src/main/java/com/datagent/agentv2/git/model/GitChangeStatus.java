package com.datagent.agentv2.git.model;

/** 冻结候选的提交授权与结果状态；PUSHED 是本能力的成功终点。 */
public enum GitChangeStatus {
    PENDING_CONFIRMATION,
    SUBMITTING,
    VERIFYING,
    PUSHED,
    RECONFIRM_REQUIRED,
    INVALIDATED,
    CANCELLED,
    FAILED
}
