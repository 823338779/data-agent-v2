package com.datagent.agentv2.dbt.application;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.model.DbtDraftSnapshot;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitCandidateValidation;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import java.util.ArrayList;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 只检查本地成功记录，通用 Git 工具和 dbt Skill 都经过此入口。 */
@RequiredArgsConstructor
public final class DbtCandidateValidation implements GitCandidateValidation {
    private static final String COMPLETE = "SUCCEEDED";
    private static final Set<String> REQUIRED_LEVELS =
            Set.of("YAML", "TEMPLATE", "DBT_PARSE", "SEMANTIC");
    private final DbtValidationPersistenceService records;
    private final DbtProperties properties;
    private final ObjectMapper json;

    /** 仅用于核对分支当前配置版本；旧无分支嵌入可为空。 */
    private final DbtServiceClient client;

    public DbtCandidateValidation(
            DbtValidationPersistenceService records, DbtProperties properties, ObjectMapper json) {
        this(records, properties, json, null);
    }

    @Override
    public GitValidationEvidence validate(String sessionId, GitDraftSnapshot git) {
        if (!sessionId.equals(git.sessionId())) throw missing();
        var draft = DbtDraftSnapshot.composeFromGit(git, properties);
        if (draft.changes().isEmpty()) return null;
        var row =
                git.branchId() == null
                        ? records.snapshot(draft.snapshotKey())
                        : records.latestBranchSnapshot(
                                sessionId,
                                git.workspaceId(),
                                git.branchId(),
                                git.baseCommitSha(),
                                git.treeId(),
                                git.draftRevision());
        if (row == null
                || !COMPLETE.equals(row.getState())
                || row.getResultJson() == null
                || !sessionId.equals(row.getConversationSessionId())
                || !git.workspaceId().equals(row.getWorkspaceId())
                || !git.baseCommitSha().equals(row.getBaseCommitSha())
                || !git.treeId().equals(row.getTreeId())
                || git.draftRevision() != row.getDraftRevision()
                || !draft.changesDigest().equals(row.getChangesDigest())
                || !properties.projectId().equals(row.getProjectId())
                || !properties.projectDirectory().equals(row.getProjectSubdir())) throw missing();
        String config = row.getConfigVersion(), toolchain = row.getToolchainVersion();
        if (git.branchId() != null
                && (!git.branchId().equals(row.getBranchId())
                        || !git.gitRef().equals(row.getGitRef())
                        || config == null
                        || config.isBlank()
                        || toolchain == null
                        || toolchain.isBlank())) throw missing();
        var result = json.readTree(row.getResultJson());
        if (!COMPLETE.equals(result.path("state").asString())
                || !result.path("valid").asBoolean(false)
                || !row.getValidationId().equals(result.path("validationId").asString())
                || !row.getBaseCommitSha().equals(result.path("baseCommitSha").asString())
                || !row.getChangesDigest().equals(result.path("changesDigest").asString())
                || !row.getProjectSubdir().equals(result.path("projectSubdir").asString()))
            throw missing();
        if (git.branchId() != null
                && (!git.branchId().equals(result.path("branchId").asString())
                        || !git.workspaceId().equals(result.path("workspaceId").asString())
                        || git.draftRevision() != result.path("draftRevision").asLong(-1)
                        || !config.equals(result.path("configVersion").asString())
                        || !toolchain.equals(result.path("toolchainVersion").asString())))
            throw missing();
        var levels = new ArrayList<String>();
        result.path("checkedLevels").forEach(level -> levels.add(level.asString()));
        if (!levels.containsAll(REQUIRED_LEVELS)) throw missing();
        if (git.branchId() != null && !levels.contains("SQL_DEFINITION")) throw missing();
        return new GitValidationEvidence(
                row.getRecordId(),
                row.getValidationId(),
                git.baseCommitSha(),
                git.treeId(),
                git.draftRevision(),
                draft.changesDigest(),
                result.path("configVersion").asString(),
                result.path("toolchainVersion").asString(),
                result.path("completedAt").asString(),
                levels,
                git.projectId(),
                git.branchId(),
                git.branchId() == null ? null : git.workspaceId(),
                git.gitRef());
    }

    @Override
    public void checkCurrent(GitValidationEvidence evidence) {
        if (evidence == null || evidence.branchId() == null) return;
        try {
            if (client == null) throw missing();
            var current = client.project(evidence.branchId());
            if (!evidence.branchId().equals(current.path("branchId").asString())
                    || !evidence.configVersion().equals(current.path("configVersion").asString())
                    || !evidence.toolchainVersion()
                            .equals(current.path("toolchainVersion").asString())) throw missing();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw missing();
        } catch (Exception unavailable) {
            throw missing();
        }
    }

    private static GitException missing() {
        return new GitException("DBT_VALIDATION_REQUIRED", "当前草稿尚无匹配的成功定义验证，请先验证再准备确认卡片");
    }
}
