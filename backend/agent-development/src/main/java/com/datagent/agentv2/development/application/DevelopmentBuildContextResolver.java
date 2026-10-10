package com.datagent.agentv2.development.application;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.dbt.port.BuildContextResolver;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 首次分析选择部署后固定build；普通会话仅使用显式受控配置，开发预览缺失不回退。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.dbt", name = "enabled", havingValue = "true")
public final class DevelopmentBuildContextResolver implements BuildContextResolver {
    private static final String MAIN = "main",
            MISSING = "DBT_BUILD_NOT_SELECTED",
            UNAVAILABLE = "DBT_UNAVAILABLE";

    /** 会话的持久构建选择。 */
    private final DevelopmentContextPersistenceService records;

    /** 持久执行级构建选择，避免会话预览完成改写运行中分析。 */
    private final com.datagent.agentv2.persistence.service.AgentExecutionService executions;

    /** 唯一服务客户端。 */
    private final DbtServiceClient client;

    /** 仅供非开发会话的明确受控构建。 */
    private final UUID ordinaryBuild;

    public DevelopmentBuildContextResolver(
            DevelopmentContextPersistenceService records,
            com.datagent.agentv2.persistence.service.AgentExecutionService executions,
            DbtServiceClient client,
            @Value("${data-agent.dbt.analysis-build-id:}") String selected) {
        this.records = records;
        this.executions = executions;
        this.client = client;
        this.ordinaryBuild =
                selected == null || selected.isBlank() ? null : UUID.fromString(selected);
    }

    @Override
    public UUID resolve(String sessionId, String executionId)
            throws com.datagent.agentv2.conversation.exception.ToolBusinessException {
        try {
            UUID pinned = executions.analysisBuild(executionId, sessionId);
            if (pinned != null) return pinned;
            return executions.selectAnalysisBuildIfAbsent(
                    executionId, sessionId, selectForSession(sessionId));
        } catch (DevelopmentException known) {
            throw new com.datagent.agentv2.conversation.exception.ToolBusinessException(
                    known.getCode(), known.getMessage());
        }
    }

    private UUID selectForSession(String sessionId) {
        var session = records.session(sessionId);
        if (session == null) {
            if (ordinaryBuild == null) throw missing();
            return ordinaryBuild;
        }
        if (session.getSelectedBuildId() != null)
            return UUID.fromString(session.getSelectedBuildId());
        // 远端读取不处于数据库事务内；空值CAS保留并发解析中的唯一获胜构建。
        try {
            var target =
                    client.currentDeployment(
                            new DeploymentKey(
                                    session.getRepository(),
                                    MAIN.equals(session.getBranchName())
                                            ? Environment.PRODUCTION
                                            : Environment.PREVIEW,
                                    session.getBranchName()));
            if (target.activeBuildId() == null) throw missing();
            return records.selectBuildIfAbsent(sessionId, target.activeBuildId());
        } catch (DevelopmentException known) {
            throw known;
        } catch (com.datagent.agentv2.conversation.exception.ToolBusinessException known) {
            throw new DevelopmentException(known.code(), known.safeMessage(), 503);
        } catch (Exception unavailable) {
            throw new DevelopmentException(UNAVAILABLE, "无法读取当前部署，请保留固定分析目标后重试", 503);
        }
    }

    private static DevelopmentException missing() {
        return new DevelopmentException(MISSING, "当前会话没有已部署的分析构建，不能回退到正式版本", 409);
    }
}
