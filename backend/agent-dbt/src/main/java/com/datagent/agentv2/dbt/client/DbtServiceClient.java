package com.datagent.agentv2.dbt.client;

import tools.jackson.databind.JsonNode;

/** 固定项目 HTTP 协议；资源文档及结果列保留 JSON 类型，禁止跨仓库依赖实现类。 */
public interface DbtServiceClient {
    default JsonNode releases(String branchId) throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode release(String branchId, String releaseId) throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

    // 分支上下文由可信执行提供；旧实现只允许显式无分支的线上调用。
    default JsonNode project(String branchId) throws Exception {
        if (branchId == null) return project();
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode catalog(String branchId, String r, String q, int page, int size)
            throws Exception {
        if (branchId == null) return catalog(r, q, page, size);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode resource(String branchId, String r, String id, String view) throws Exception {
        if (branchId == null) return resource(r, id, view);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode submitOptions(String branchId, JsonNode body) throws Exception {
        if (branchId == null) return submitOptions(body);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode getOptions(String branchId, String id) throws Exception {
        if (branchId == null) return getOptions(id);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode submitQuery(String branchId, JsonNode body) throws Exception {
        if (branchId == null) return submitQuery(body);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode queryStatus(String branchId, String id) throws Exception {
        if (branchId == null) return queryStatus(id);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode queryPage(String branchId, String id, int offset, int limit) throws Exception {
        if (branchId == null) return queryPage(id, offset, limit);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode submitValidation(String branchId, JsonNode body) throws Exception {
        if (branchId == null) return submitValidation(body);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode getValidation(String branchId, String id) throws Exception {
        if (branchId == null) return getValidation(id);
        throw new UnsupportedOperationException("分支能力未实现");
    }

    // 旧客户端实现可继续提供线上分析，新分支方法缺失时必须明确失败。
    default JsonNode branches() throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode branch(String branchId) throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode createBranch(JsonNode request) throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode registerBranch(JsonNode request) throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

    default JsonNode deleteBranch(String branchId, long expectedVersion) throws Exception {
        throw new UnsupportedOperationException("分支能力未实现");
    }

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
