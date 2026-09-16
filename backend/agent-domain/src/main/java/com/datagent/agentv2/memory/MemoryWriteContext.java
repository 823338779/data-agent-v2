package com.datagent.agentv2.memory;

import java.util.Objects;

/** 服务端生成的写入调用上下文；身份始终从固定 Tenant/Operator 上下文解析。 */
public record MemoryWriteContext(
        Caller caller,
        String requestId,
        String executionId,
        String conversationId,
        Long expectedRevision,
        MemoryReviewClaim reviewClaim,
        /** 服务端冻结的实际提示词版本，旧调用未提供时为空；不作为模型工具参数。 */
        String promptVersion) {
    public MemoryWriteContext(
            Caller caller,
            String requestId,
            String executionId,
            String conversationId,
            Long expectedRevision,
            MemoryReviewClaim reviewClaim) {
        this(caller, requestId, executionId, conversationId, expectedRevision, reviewClaim, null);
    }

    public enum Caller {
        UI,
        MAIN,
        BACKGROUND
    }

    public MemoryWriteContext {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(requestId, "requestId");
        if (expectedRevision != null && expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不得为负数");
        }
        if ((caller == Caller.BACKGROUND) != (reviewClaim != null)) {
            throw new IllegalArgumentException("后台调用必须携带真实领取凭证");
        }
    }
}
