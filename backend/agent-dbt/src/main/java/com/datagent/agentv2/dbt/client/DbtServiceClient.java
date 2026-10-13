package com.datagent.agentv2.dbt.client;

import com.datagent.agentv2.dbt.model.DbtContracts.*;

import java.util.UUID;

/** 唯一执行快照 HTTP端口；调用方固定构建与输入，构建写入保留幂等键，不隐式重试或降级。 */
public interface DbtServiceClient {
    BuildView submitBuild(BuildRequest request) throws Exception;

    BuildView getBuild(UUID buildId) throws Exception;

    DeploymentTargetView currentDeployment(DeploymentKey key) throws Exception;

    DeploymentPage listDeployments(DeploymentFilter filter) throws Exception;

    CatalogPage catalog(UUID buildId, CatalogFilter filter) throws Exception;

    ResourceView resource(UUID buildId, String resourceId) throws Exception;

    ResourceSourceView resourceSource(UUID buildId, String resourceId) throws Exception;

    ResourceLineageView resourceLineage(UUID buildId, String resourceId) throws Exception;

    ResourceNativeView resourceNative(UUID buildId, String resourceId) throws Exception;

    AvailableDimensionsView submitOptions(UUID buildId, AvailableDimensionsRequest request)
            throws Exception;

    QueryResult submitQuery(UUID buildId, QueryRequest request) throws Exception;
}
