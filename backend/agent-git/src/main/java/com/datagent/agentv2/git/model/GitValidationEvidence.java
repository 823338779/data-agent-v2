package com.datagent.agentv2.git.model;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 与候选一同冻结的验证证明；不能用远端 validationId 单独授权提交。 */
public record GitValidationEvidence(
        String recordId,
        String validationId,
        String baseCommitSha,
        String treeId,
        long draftRevision,
        String changesDigest,
        String configVersion,
        String toolchainVersion,
        String validatedAt,
        List<String> checkedLevels,
        /** 固定项目，历史凭据为空。 */
        String projectId,
        /** 固定分支实例，历史凭据为空。 */
        String branchId,
        /** 固定工作区，历史凭据为空。 */
        String workspaceId,
        /** 固定完整引用，历史凭据为空。 */
        String gitRef) {
    private static final JsonMapper JSON = new JsonMapper();

    public GitValidationEvidence {
        checkedLevels = List.copyOf(checkedLevels);
    }

    public GitValidationEvidence(
            String recordId,
            String validationId,
            String baseCommitSha,
            String treeId,
            long draftRevision,
            String changesDigest,
            String configVersion,
            String toolchainVersion,
            String validatedAt,
            List<String> checkedLevels) {
        this(
                recordId,
                validationId,
                baseCommitSha,
                treeId,
                draftRevision,
                changesDigest,
                configVersion,
                toolchainVersion,
                validatedAt,
                checkedLevels,
                null,
                null,
                null,
                null);
    }

    public String encode() {
        return JSON.writeValueAsString(this);
    }

    public static GitValidationEvidence decode(String value) {
        if (value == null) return null;
        try {
            return JSON.readValue(value, GitValidationEvidence.class);
        } catch (RuntimeException invalid) {
            throw new GitException("DBT_VALIDATION_MISMATCH", "候选验证证据损坏");
        }
    }
}
