package com.datagent.agentv2.persistence.entity.git;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 会话独占草稿及完整性元数据；仅持久化层执行条件更新。 */
@Getter
@Setter
@TableName("git_workspace")
public final class GitWorkspaceEntity extends TenantAuditedEntity {
    /** 服务端生成的工作区业务标识，一个会话与仓库组合唯一。 */
    private String workspaceId;

    /** 工作区归属会话，所有文件操作都先通过会话查找工作区。 */
    private String conversationSessionId;

    /** 固定配置仓库标识。 */
    private String repositoryId;

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
}
