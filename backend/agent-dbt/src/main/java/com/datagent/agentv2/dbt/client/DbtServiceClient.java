package com.datagent.agentv2.dbt.client;

import com.datagent.agentv2.dbt.model.DbtContracts.*;
import java.util.UUID;

/** 唯一v3 HTTP端口；调用方冻结输入及幂等键，不隐式重试或降级。 */
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

    OptionsTaskView submitOptions(UUID buildId, OptionsRequest request) throws Exception;

    OptionsTaskView getOptions(UUID optionsTaskId) throws Exception;

    QueryView submitQuery(UUID buildId, QueryRequest request) throws Exception;

    QueryView queryStatus(UUID queryId) throws Exception;

    ResultPage queryResults(UUID queryId, int offset, int limit) throws Exception;
}
