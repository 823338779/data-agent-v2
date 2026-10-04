package com.datagent.agentv2.git.port;

import tools.jackson.databind.JsonNode;

/** 分支身份由 dbt 服务登记；Agent 只能通过受控 HTTP 适配器访问。 */
public interface GitBranchRegistry {
    JsonNode list();

    JsonNode get(String branchId);

    JsonNode releases(String branchId);

    JsonNode release(String branchId, String releaseId);

    JsonNode create(JsonNode request);

    JsonNode register(JsonNode request);

    JsonNode delete(String branchId, long expectedVersion);
}
