package com.datagent.agentv2.context.hierarchical.domain;

/** 安全原文的持久化凭据；不可用状态绝不携带可读取的假引用。 */
public record ContextPayloadReference(
        /** 不透明标识，只有 AVAILABLE 才非空。 */
        String reference,
        /** 是否已完整归档。 */
        Availability availability,
        /** 安全原文 UTF-8 总字节数。 */
        long totalBytes,
        /** 安全原文 SHA-256。 */
        String sha256,
        /** 截断前从安全 JSON 顶层保留的操作身份和状态，不包含正文或授权。 */
        java.util.Map<String, String> observedFacts) {
    public static final java.util.Set<String> OBSERVED_FIELDS =
            java.util.Set.of(
                    "operationId",
                    "buildId",
                    "optionsTaskId",
                    "repository",
                    "branchName",
                    "pushedSha",
                    "mergeSha",
                    "buildStatus",
                    "deploymentStatus",
                    "phase",
                    "validationId",
                    "queryId",
                    "changeId",
                    "workspaceId",
                    "projectId",
                    "branchId",
                    "gitRef",
                    "revision",
                    "draftRevision",
                    "status",
                    "state",
                    "valid",
                    "errorCode");

    public ContextPayloadReference(
            String reference, Availability availability, long totalBytes, String sha256) {
        this(reference, availability, totalBytes, sha256, java.util.Map.of());
    }

    public ContextPayloadReference {
        observedFacts =
                observedFacts == null ? java.util.Map.of() : java.util.Map.copyOf(observedFacts);
        if (availability == null
                || totalBytes < 0
                || sha256 == null
                || (availability == Availability.AVAILABLE)
                        != (reference != null && !reference.isBlank())) {
            throw new IllegalArgumentException("Invalid context payload reference");
        }
    }

    public enum Direction {
        INPUT,
        OUTPUT
    }

    public enum Availability {
        AVAILABLE,
        OVERSIZE,
        MISSING
    }
}
