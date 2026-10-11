package com.datagent.agentv2.development.application;

import static com.datagent.agentv2.development.model.DevelopmentContracts.SCHEMA_REQUIRED;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.persistence.entity.development.*;
import com.datagent.agentv2.persistence.service.development.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 预览与交付共用固定构建尝试推进；不推断外部业务成功，只消费构建和部署事实。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-agent.dbt", name = "enabled", havingValue = "true")
public class DevelopmentBuildService {
    private static final String MAIN = "main",
            SEPARATOR = ":build:",
            PROTOCOL = "DBT_PROTOCOL_INVALID";
    private static final String RESULT_UNKNOWN = "DBT_RESULT_UNKNOWN",
            BUILD_UNKNOWN = "BUILD_OUTCOME_UNKNOWN",
            INVALID_REQUEST = "INVALID_REQUEST";

    /** 同一持久动作的受理与CAS推进。 */
    private final DevelopmentOperations operations;

    /** 构建尝试与操作状态原子记录。 */
    private final DevelopmentOperationPersistenceService records;

    /** 仅给后续分析流程更新会话的默认构建。 */
    private final DevelopmentContextPersistenceService contexts;

    /** 固定引擎协议客户端。 */
    private final DbtServiceClient client;

    public OperationView reconcile(DevelopmentOperationEntity row, Environment environment) {
        boolean production = environment == Environment.PRODUCTION;
        Phase submitting = production ? Phase.RELEASE_SUBMITTING : Phase.BUILD_SUBMITTING;
        Phase running = production ? Phase.RELEASING : Phase.BUILD_RUNNING;
        Phase done = production ? Phase.RELEASED : Phase.DEPLOYED;
        var context = operations.context(row);
        var config = context.configSnapshot();
        // 升级前受理的快照不能擅自补写目标 schema；释放原操作，要求配置后新建意图。
        if (row.getBuildId() == null
                && (config.previewSchemaName() == null || config.productionSchemaName() == null))
            return operations.fail(row, OperationStatus.FAILED, SCHEMA_REQUIRED);
        String sha = production ? row.getMergeSha() : row.getPushedSha();
        if (sha == null) throw new DevelopmentException(PROTOCOL, "构建前必须核实已推送的固定 SHA", 409);
        var request =
                new BuildRequest(
                        context.repository(),
                        production ? MAIN : context.branchName(),
                        sha,
                        environment,
                        production ? config.productionBinding() : config.previewBinding(),
                        production
                                ? config.productionConfigVersion()
                                : config.previewConfigVersion(),
                        production ? config.productionSchemaName() : config.previewSchemaName(),
                        DeploymentPolicy.ON_SUCCESS,
                        row.getOperationId() + SEPARATOR + row.getAttemptNumber(),
                        new Origin(context.sessionId(), row.getOperationId()));
        BuildView build;
        try {
            if (row.getBuildId() == null) {
                row = operations.update(row, submitting, OperationStatus.RUNNING, null);
                build = client.submitBuild(request);
                validate(build, request);
                var attempt = new DevelopmentBuildAttemptEntity();
                attempt.setOperationId(row.getOperationId());
                attempt.setAttemptNumber(row.getAttemptNumber());
                attempt.setBuildId(build.buildId().toString());
                attempt.setCommitSha(sha);
                attempt.setEnvironment(environment.name());
                attempt.setIdempotencyKey(request.idempotencyKey());
                row.setBuildId(build.buildId().toString());
                row.setPhase(running.name());
                if (!records.recordBuild(row, attempt))
                    throw new DevelopmentException(PROTOCOL, "操作版本已变化", 409);
                row = operations.require(UUID.fromString(row.getOperationId()));
            } else {
                build = client.getBuild(UUID.fromString(row.getBuildId()));
                validate(build, request);
                if (!row.getBuildId().equals(build.buildId().toString()))
                    throw new DevelopmentException(PROTOCOL, "构建身份发生变化", 409);
            }
        } catch (DevelopmentException conflict) {
            throw conflict;
        } catch (Exception unknown) {
            String code =
                    unknown instanceof ToolBusinessException business
                            ? business.code()
                            : RESULT_UNKNOWN;
            // 明确的参数拒绝未受理构建；只有响应丢失等不确定结果才继续使用原请求键恢复。
            return operations.view(
                    operations.update(
                            row,
                            row.getBuildId() == null ? submitting : running,
                            row.getBuildId() == null && INVALID_REQUEST.equals(code)
                                    ? OperationStatus.FAILED
                                    : OperationStatus.RESULT_UNKNOWN,
                            code));
        }
        if (build.buildStatus() == BuildStatus.FAILED
                || build.buildStatus() == BuildStatus.CANCELLED) {
            return operations.view(
                    operations.update(
                            row,
                            running,
                            OperationStatus.FAILED,
                            build.error() == null
                                    ? build.buildStatus().name()
                                    : build.error().code()));
        }
        if (build.buildStatus() == BuildStatus.OUTCOME_UNKNOWN) {
            return operations.view(
                    operations.update(row, running, OperationStatus.RESULT_UNKNOWN, BUILD_UNKNOWN));
        }
        if (build.buildStatus() != BuildStatus.SUCCEEDED) {
            return operations.view(operations.update(row, running, OperationStatus.RUNNING, null));
        }
        var deployment = build.initialDeployment();
        if (deployment == null
                || !build.buildId().equals(deployment.buildId())
                || !request.repository().equals(deployment.repository())
                || environment != deployment.environment()
                || !request.branchName().equals(deployment.branchName())) {
            return operations.view(
                    operations.update(
                            row, Phase.DEPLOYMENT_PENDING, OperationStatus.BLOCKED, PROTOCOL));
        }
        if (deployment.deploymentStatus() == DeploymentStatus.DEPLOYED) {
            if (!production) {
                var session = contexts.session(row.getSessionId());
                session.setSelectedBuildId(build.buildId().toString());
                if (!contexts.replaceSession(session))
                    throw new DevelopmentException(PROTOCOL, "会话版本已变化", 409);
            }
            return operations.view(operations.update(row, done, OperationStatus.SUCCEEDED, null));
        }
        if (Set.of(DeploymentStatus.STALE, DeploymentStatus.FAILED, DeploymentStatus.CANCELLED)
                .contains(deployment.deploymentStatus())) {
            return operations.view(
                    operations.update(
                            row,
                            Phase.DEPLOYMENT_PENDING,
                            OperationStatus.BLOCKED,
                            deployment.deploymentStatus().name()));
        }
        return operations.view(
                operations.update(row, Phase.DEPLOYMENT_PENDING, OperationStatus.RUNNING, null));
    }

    private static void validate(BuildView build, BuildRequest request) {
        if (build == null
                || build.buildId() == null
                || build.buildStatus() == null
                || !request.repository().equals(build.repository())
                || !request.branchName().equals(build.branchName())
                || !request.commitSha().equals(build.requestedCommitSha())
                || build.commitSha() != null && !request.commitSha().equals(build.commitSha())
                || request.environment() != build.environment()
                || !request.executionBinding().equals(build.executionBinding())
                || !request.configVersion().equals(build.configVersion())) {
            throw new DevelopmentException(PROTOCOL, "引擎响应不属于固定构建输入", 409);
        }
    }
}
