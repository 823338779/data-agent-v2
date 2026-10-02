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
        List<String> checkedLevels) {
    private static final JsonMapper JSON = new JsonMapper();

    public GitValidationEvidence {
        checkedLevels = List.copyOf(checkedLevels);
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
