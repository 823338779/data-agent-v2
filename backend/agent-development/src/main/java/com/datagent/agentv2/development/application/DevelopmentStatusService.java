package com.datagent.agentv2.development.application;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.model.DevelopmentOverview;
import com.datagent.agentv2.development.model.DevelopmentOverview.*;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.persistence.service.development.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 只聚合可观察事实；轮询与SSE均不驱动动作状态机。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class DevelopmentStatusService {
    private final DevelopmentContextService contexts;
    private final DevelopmentContextPersistenceService sessions;
    private final DevelopmentOperationPersistenceService records;
    private final DevelopmentOperations operations;
    private final DevelopmentWorkspaceService files;
    private final GitRepositoryClient git;
    private final ObjectProvider<DbtServiceClient> clients;

    public DevelopmentOverview getOverview(String session) {
        var context = contexts.get(session);
        try {
            context = contexts.forNewOperation(session);
        } catch (com.datagent.agentv2.development.model.DevelopmentException disabled) {
            /* 历史仍使用冻结绑定展示。 */
        }
        var row = sessions.session(session);
        var history = records.forSession(session);
        Remote remote;
        DeliveryScope scope = null;
        try {
            var branch = git.describeBranch(context.repository(), context.branchName());
            remote = new Remote(branch.sourceSha(), branch.mainSha(), Instant.now(), null);
            scope = DeliveryScope.compose(context, branch);
        } catch (GitException failure) {
            remote = new Remote(null, null, Instant.now(), failure.code());
        }
        BuildView latest = null;
        DeploymentTargetView deployment = null;
        var client = clients.getIfAvailable();
        if (client != null) {
            var last = history.stream().filter(op -> op.getBuildId() != null).findFirst();
            try {
                if (last.isPresent())
                    latest = client.getBuild(UUID.fromString(last.get().getBuildId()));
                deployment =
                        client.currentDeployment(
                                new DeploymentKey(
                                        context.repository(),
                                        Environment.PREVIEW,
                                        context.branchName()));
            } catch (Exception unavailable) {
                // 读取失败不能被解释为没有部署；调用方重试概览读取。
                throw new com.datagent.agentv2.development.model.DevelopmentException(
                        "DBT_READ_UNAVAILABLE", "构建或部署事实暂不可读", 503);
            }
        }
        return new DevelopmentOverview(
                context,
                new Local(files.inspect(session), row.getRevision(), row.getPushedSha()),
                remote,
                latest,
                deployment,
                scope,
                history.stream().map(operations::view).toList());
    }
}
