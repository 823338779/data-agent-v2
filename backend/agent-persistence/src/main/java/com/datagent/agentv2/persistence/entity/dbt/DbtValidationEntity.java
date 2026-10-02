package com.datagent.agentv2.persistence.entity.dbt;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 固定快照的耐久校验尝试与凭证，不保存 YAML 或服务远端凭据。 */
@Getter
@Setter
@TableName("dbt_validation")
public final class DbtValidationEntity extends TenantAuditedEntity {
    /** 本地记录身份，也是幂等键的一部分。 */
    private String recordId;

    /** 绑定会话、工作区、项目、基线、tree 和修订号，数据库唯一。 */
    private String snapshotKey;

    private String conversationSessionId;
    private String workspaceId;
    private String projectId;

    /** 配置项目相对 Git 根目录；不能用相似路径前缀代替。 */
    private String projectSubdir;

    private String baseCommitSha;
    private String treeId;
    private Long draftRevision;
    private String changesDigest;

    /** 只有明确 FAILED 才递增；UNKNOWN 重试保持不变。 */
    private Long attempt;

    /** UNKNOWN/QUEUED/RUNNING/SUCCEEDED/FAILED；UNKNOWN 表示受理回执尚不确定。 */
    private String state;

    /** 服务任务 ID，受理回执丢失时为空。 */
    private String validationId;

    /** 已核对绑定字段的完整有限响应；为空不能作为成功证据。 */
    private String resultJson;
}
