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
}
