package com.datagent.agentv2.conversation.cluster;

import static com.datagent.agentv2.conversation.RunConstants.CONTROL_CANCEL;

import java.time.Instant;
import java.util.Objects;

/** 跨实例取消唤醒消息；数据库 Run 状态仍是唯一控制事实来源。 */
public record RunControlEnvelope(
        String notificationId,
        String tenantId,
        String runId,
        String ownerInstanceId,
        long executionAttempt,
        long controlVersion,
        Instant requestedAt,
        String type) {

    public RunControlEnvelope {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (!CONTROL_CANCEL.equals(type) || executionAttempt < 1 || controlVersion < 1) {
            throw new IllegalArgumentException("invalid run control envelope");
        }
    }
}
