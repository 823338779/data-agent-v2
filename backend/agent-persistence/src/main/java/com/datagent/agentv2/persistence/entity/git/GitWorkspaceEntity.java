package com.datagent.agentv2.persistence.entity.git;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 独立草稿及固定分支元数据；会话通过关联表选择，目标不能随选择改变。 */
@Getter
@Setter
@TableName("git_workspace")
public final class GitWorkspaceEntity extends TenantAuditedEntity {
    private static final String WORKSPACE_PREFIX = "gw_";
    private static final String STORAGE_PREFIX = "workspaces/";
    private static final String MUTATING = "MUTATING";

    /** 服务端生成的独立工作区业务标识。 */
    private String workspaceId;

    /** 最初创建会话，仅用于审计，不代表独占所有权。 */
    private String conversationSessionId;

    /** 固定配置仓库标识。 */
    private String repositoryId;

    /** dbt 服务配置拥有的逻辑项目，历史未登记工作区为空。 */
    private String projectId;

    /** 创建时固定的 dbt 分支实例，历史 main 工作区为空。 */
    private String branchId;

    /** 固定完整远端引用，禁止使用会话当前选择覆盖。 */
    private String gitRef;

    /** 迁移的 main 工作区只能读取旧材料，不能开始新的开发写入。 */
    private Boolean legacyReadOnly;

    /** 最初创建会话内的幂等键，同键恢复原工作区。 */
    private String creationKey;

    /** 创建输入摘要，约束同键的项目、分支和基线不变。 */
    private String creationDigest;

    /** 服务端生成的 clone 存储键，显式重新开始会更换；旧目录保留。 */
    private String storageKey;

    /** 冻结草稿的远端基线，不随重试改变。 */
    private String baseCommit;

    /** 单调递增草稿修订号，任何实际编辑均使旧候选失效。 */
    private Long draftRevision;

    /** 工作目录全部文件的摘要，排除 .git；检测服务重启或外部修改。 */
    private String draftDigest;

    /** 当前候选 ID；提交和核实阶段同时作为工作区编辑屏障。 */
    private String activeChangeId;

    /** READY 可使用，MUTATING 为文件操作中间屏障，BROKEN 必须显式重新开始。 */
    private String status;

    /** CAS 单调版本；重复按钮只接受已完成转换的原版本。 */
    private Long version;

    /** 组装创建意图；身份由后端生成，项目及引用由分支服务核实。 */
    public static GitWorkspaceEntity composeForCreation(
            String sessionId,
            String repositoryId,
            String projectId,
            String branchId,
            String gitRef,
            String baseCommit,
            String creationKey,
            String creationDigest) {
        var workspace = new GitWorkspaceEntity();
        workspace.setWorkspaceId(WORKSPACE_PREFIX + java.util.UUID.randomUUID());
        workspace.setStorageKey(STORAGE_PREFIX + java.util.UUID.randomUUID());
        workspace.setConversationSessionId(sessionId);
        workspace.setRepositoryId(repositoryId);
        workspace.setProjectId(projectId);
        workspace.setBranchId(branchId);
        workspace.setGitRef(gitRef);
        workspace.setBaseCommit(baseCommit);
        workspace.setCreationKey(creationKey);
        workspace.setCreationDigest(creationDigest);
        workspace.setLegacyReadOnly(false);
        workspace.setDraftRevision(0L);
        workspace.setVersion(0L);
        workspace.setStatus(MUTATING);
        return workspace;
    }
}
