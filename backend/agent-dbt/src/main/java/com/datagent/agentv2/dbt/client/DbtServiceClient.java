package com.datagent.agentv2.dbt.client;

import tools.jackson.databind.JsonNode;

/** 固定项目 HTTP 协议；资源文档及结果列保留 JSON 类型，禁止跨仓库依赖实现类。 */
public interface DbtServiceClient {
    JsonNode project() throws Exception;

    JsonNode catalog(String releaseId, String query, int page, int size) throws Exception;

    JsonNode resource(String releaseId, String resourceId, String view) throws Exception;

    JsonNode submitOptions(JsonNode request) throws Exception;

    JsonNode getOptions(String optionsJobId) throws Exception;

    JsonNode submitQuery(JsonNode request) throws Exception;

    JsonNode queryStatus(String queryId) throws Exception;

    JsonNode queryPage(String queryId, int offset, int limit) throws Exception;

    JsonNode submitValidation(JsonNode request) throws Exception;

    JsonNode getValidation(String validationId) throws Exception;
}
