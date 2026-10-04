package com.datagent.agentv2.dbt.application;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.model.*;
import com.datagent.agentv2.dbt.model.DbtContracts.BranchValidationRequest;
import com.datagent.agentv2.dbt.model.DbtContracts.ValidationRequest;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.model.GitWorkspaceContext;
import com.datagent.agentv2.persistence.entity.dbt.DbtValidationEntity;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 冻结、持久受理意图、远端校验和证据登记；不持有跨网络事务或工作区锁。 */
@RequiredArgsConstructor
public final class DbtValidationService {
    private static final String UNKNOWN = "UNKNOWN", SUCCEEDED = "SUCCEEDED";
    private static final String VALIDATION_ID = "validationId", STATE = "state";
    private static final Set<String> STATES = Set.of("QUEUED", "RUNNING", SUCCEEDED, "FAILED");
    private static final String BRANCH = "branchId",
            WORKSPACE = "workspaceId",
            REVISION = "draftRevision";
    private static final String CONFIG = "configVersion", TOOLCHAIN = "toolchainVersion";
    private static final String BRANCH_CAPABILITY = "branch-development-v1";
    private final GitWorkspaceService workspaces;
    private final DbtValidationPersistenceService records;
    private final DbtServiceClient client;
    private final DbtProperties properties;
    private final ObjectMapper json;

    public JsonNode submit(String sessionId, long expectedRevision) throws Exception {
        var snapshot =
                DbtDraftSnapshot.composeFromGit(
                        workspaces.freezeDraft(sessionId, expectedRevision), properties);
        return submitSnapshot(sessionId, snapshot);
    }

    /** 执行固定身份先核对本地快照，后续 HTTP 只使用冻结参数。 */
    public JsonNode submit(GitWorkspaceContext context, String sessionId, long expectedRevision)
            throws Exception {
        var git =
                workspaces
                        .freezeDraft(context.workspaceId(), expectedRevision)
                        .forSession(sessionId);
        if (!context.branchId().equals(git.branchId())
                || !context.gitRef().equals(git.gitRef())
                || !context.projectId().equals(git.projectId())) throw protocolError();
        return submitSnapshot(sessionId, DbtDraftSnapshot.composeFromGit(git, properties));
    }

    private JsonNode submitSnapshot(String sessionId, DbtDraftSnapshot snapshot) throws Exception {
        if (snapshot.changes().isEmpty())
            throw new ToolBusinessException("DBT_EMPTY_CHANGE", "没有受管项目 SQL/YAML 变更");
        // 所有正文已在锁内冻结。能力检查和提交在锁外，期间编辑只会让证据过时。
        String branchId = snapshot.git().branchId();
        var project = branchId == null ? client.project() : client.project(branchId);
        checkCapabilities(project, branchId);
        var candidate = new DbtValidationEntity();
        candidate.setRecordId(UUID.randomUUID().toString());
        candidate.setSnapshotKey(
                snapshot.validationKey(
                        project.path(CONFIG).asString(), project.path(TOOLCHAIN).asString()));
        candidate.setConversationSessionId(sessionId);
        candidate.setWorkspaceId(snapshot.git().workspaceId());
        candidate.setProjectId(properties.projectId());
        candidate.setBranchId(branchId);
        candidate.setGitRef(snapshot.git().gitRef());
        if (branchId != null) {
            candidate.setConfigVersion(project.path(CONFIG).asString());
            candidate.setToolchainVersion(project.path(TOOLCHAIN).asString());
        }
        candidate.setProjectSubdir(properties.projectDirectory());
        candidate.setBaseCommitSha(snapshot.git().baseCommitSha());
        candidate.setTreeId(snapshot.git().treeId());
        candidate.setDraftRevision(snapshot.git().draftRevision());
        candidate.setChangesDigest(snapshot.changesDigest());
        candidate.setAttempt(0L);
        candidate.setState(UNKNOWN);
        var record = records.reserve(candidate);
        if (record.getValidationId() != null) return get(sessionId, record.getValidationId());
        String key = "agent-dbt:validation:" + record.getRecordId() + ":" + record.getAttempt();
        var receipt =
                branchId == null
                        ? client.submitValidation(
                                json.valueToTree(
                                        new ValidationRequest(
                                                snapshot.git().baseCommitSha(),
                                                key,
                                                snapshot.changes())))
                        : client.submitValidation(
                                branchId,
                                json.valueToTree(
                                        new BranchValidationRequest(
                                                snapshot.git().workspaceId(),
                                                snapshot.git().draftRevision(),
                                                snapshot.git().baseCommitSha(),
                                                key,
                                                snapshot.changes())));
        validateReceipt(receipt);
        records.record(
                record,
                receipt.path(VALIDATION_ID).asString(),
                receipt.path(STATE).asString(),
                null);
        return get(sessionId, receipt.path(VALIDATION_ID).asString());
    }

    public JsonNode get(String sessionId, String validationId) throws Exception {
        var record = records.remote(sessionId, validationId);
        if (record == null || !properties.projectId().equals(record.getProjectId()))
            throw new ToolBusinessException("DBT_VALIDATION_NOT_FOUND", "当前会话验证不存在");
        if (SUCCEEDED.equals(record.getState()) && record.getResultJson() != null)
            return json.readTree(record.getResultJson());
        var result =
                record.getBranchId() == null
                        ? client.getValidation(validationId)
                        : client.getValidation(record.getBranchId(), validationId);
        validateReceipt(result);
        if (record.getBranchId() != null
                && (!record.getBranchId().equals(result.path(BRANCH).asString())
                        || !record.getWorkspaceId().equals(result.path(WORKSPACE).asString())
                        || !result.path(REVISION).isIntegralNumber()
                        || record.getDraftRevision() != result.path(REVISION).asLong()
                        || !record.getConfigVersion().equals(result.path(CONFIG).asString())
                        || !record.getToolchainVersion().equals(result.path(TOOLCHAIN).asString())))
            throw protocolError();
        if (!validationId.equals(result.path(VALIDATION_ID).asString())
                || !record.getBaseCommitSha().equals(result.path("baseCommitSha").asString())
                || !record.getChangesDigest().equals(result.path("changesDigest").asString())
                || !record.getProjectSubdir().equals(result.path("projectSubdir").asString())
                || result.path("configVersion").asString("").isBlank()
                || result.path("toolchainVersion").asString("").isBlank()) throw protocolError();
        if (SUCCEEDED.equals(result.path(STATE).asString())
                && (!result.path("valid").isBoolean()
                        || result.path("valid").asBoolean()
                                && (!result.path("validatedProjectDigest")
                                                .asString("")
                                                .matches("[a-f0-9]{64}")
                                        || !result.path("checkedLevels").isArray())))
            throw protocolError();
        records.record(
                record,
                validationId,
                result.path(STATE).asString(),
                json.writeValueAsString(result));
        return result;
    }

    /** 验证轮询也固定工作区，不能用同会话其他工作区的任务冒充当前证据。 */
    public JsonNode get(GitWorkspaceContext context, String sessionId, String validationId)
            throws Exception {
        var row = records.remote(sessionId, validationId);
        if (row == null
                || !context.workspaceId().equals(row.getWorkspaceId())
                || !context.branchId().equals(row.getBranchId())
                || !context.gitRef().equals(row.getGitRef())) {
            throw new ToolBusinessException("DBT_VALIDATION_NOT_FOUND", "当前工作区验证不存在");
        }
        return get(sessionId, validationId);
    }

    private void checkCapabilities(JsonNode project, String branchId) throws ToolBusinessException {
        boolean capability = false;
        boolean branchCapability = branchId == null;
        for (JsonNode value : project.path("capabilities")) {
            branchCapability |= BRANCH_CAPABILITY.equals(value.asString());
            capability |=
                    value.asString()
                            .equals(
                                    branchId == null
                                            ? "draft-validation-v1"
                                            : "draft-validation-v2");
        }
        if (branchId != null
                && (!branchId.equals(project.path(BRANCH).asString())
                        || project.path(CONFIG).asString("").isBlank()
                        || project.path(TOOLCHAIN).asString("").isBlank())) throw protocolError();
        if (!project.path("protocolVersion").asString().equals("agent-dbt-v1")
                || !capability
                || !branchCapability
                || !properties
                        .projectDirectory()
                        .equals(project.path("projectSubdir").asString())) {
            throw new ToolBusinessException("DBT_CAPABILITY_UNSUPPORTED", "服务验证能力或项目子目录与配置不匹配");
        }
    }

    private static void validateReceipt(JsonNode result) throws ToolBusinessException {
        try {
            UUID.fromString(result.path(VALIDATION_ID).asString());
        } catch (IllegalArgumentException invalid) {
            throw protocolError();
        }
        if (!STATES.contains(result.path(STATE).asString())) throw protocolError();
    }

    private static ToolBusinessException protocolError() {
        return new ToolBusinessException("DBT_VALIDATION_MISMATCH", "服务验证响应与冻结快照不一致，不能准备提交");
    }
}
