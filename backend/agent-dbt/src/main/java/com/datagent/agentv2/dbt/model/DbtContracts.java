package com.datagent.agentv2.dbt.model;

import java.util.List;

/** 固定验证请求字段；正文仅存在于传输和冻结快照，不复制入验证索引。 */
public final class DbtContracts {
    private DbtContracts() {}

    /** expectedSha256 仅 UPDATE/DELETE 必填；content 仅 DELETE 为空。 */
    public record DraftChange(
            String path, String operation, String expectedSha256, String content) {}

    public record ValidationRequest(
            String baseCommitSha, String idempotencyKey, List<DraftChange> changes) {
        public ValidationRequest {
            changes = List.copyOf(changes);
        }
    }

    /** 分支来自 HTTP 路径，其余固定上下文来自可信执行冻结的工作区。 */
    public record BranchValidationRequest(
            /** 独立工作区身份。 */
            String workspaceId,
            /** 冻结草稿修订号。 */
            long draftRevision,
            /** 冻结基线。 */
            String baseCommitSha,
            /** 本地持久尝试的幂等键。 */
            String idempotencyKey,
            /** 完整 SQL/YAML/test 文件变化。 */
            List<DraftChange> changes) {
        public BranchValidationRequest {
            changes = List.copyOf(changes);
        }
    }
}
