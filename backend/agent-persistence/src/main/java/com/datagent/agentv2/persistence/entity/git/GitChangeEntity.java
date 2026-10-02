package com.datagent.agentv2.persistence.entity.git;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 冻结候选、确认授权和可恢复提交元数据；仅持久化层执行条件更新。 */
@Getter
@Setter
@TableName("git_change")
public final class GitChangeEntity extends TenantAuditedEntity {
    /** 与候选快照共同封存的校验证据 JSON；历史或非 dbt 候选为空。 */
    private String validationEvidence;

    /** 首次确认请求版本；状态进展后仍能识别同一按钮请求。 */
    private Long confirmationVersion;

    /** 最近重试请求版本，重复重试不得再次排队。 */
    private Long retryVersion;

    /** 取消请求版本，用于终态按钮幂等。 */
    private Long cancelVersion;

    /** 服务端生成的候选业务 ID，也是卡片 payload 的唯一引用。 */
    private String changeId;

    /** 所属草稿工作区，不能通过此 ID 绕过会话归属校验。 */
    private String workspaceId;

    /** 可信 execution 上下文解析出的会话。 */
    private String conversationSessionId;

    /** 发布 port 在短事务中关联的 assistant 消息；正式登记后必须非空。 */
    private String conversationMessageId;

    /** 固定配置仓库标识。 */
    private String repositoryId;

    /** 目标分支固定为 main。 */
    private String branch;

    /** 冻结草稿的远端基线，不随重试改变。 */
    private String baseCommit;

    /** 冻结 Git tree 对象 ID，不是 commit。 */
    private String treeId;

    /** 不可变审阅材料存储键，不得向客户端暴露。 */
    private String snapshotStorageKey;

    /** 完整快照文件 SHA-256，文件缺失或不符时禁止确认。 */
    private String snapshotDigest;

    /** 绑定仓库、main、基线、tree、说明和 diff 的候选摘要。 */
    private String candidateDigest;

    /** 用户审阅的业务变更摘要，与提交说明一并冻结。 */
    private String summary;

    /** 用户确认的原始提交说明，重试时禁止重新生成或改写。 */
    private String commitMessage;

    /** 单调递增草稿修订号，任何实际编辑均使旧候选失效。 */
    private Long draftRevision;

    /** 候选包含的全部变更文件数量。 */
    private Integer fileCount;

    /** 授权与推送状态，取值遵循 GitChangeStatus，版本 CAS 控制状态迁移。 */
    private String status;

    /** 用户确认时间，空值代表没有提交授权。 */
    private Instant confirmedAt;

    /** 固定运行身份，只有确认接口能够写入。 */
    private String confirmedBy;

    /** 创建候选时冻结的服务端作者名，部署配置变动不得影响重试。 */
    private String authorName;

    /** 创建候选时冻结的服务端作者邮箱，同时用作 committer 邮箱。 */
    private String authorEmail;

    /** 首次确认固定的秒级 UTC 提交时间，重试不得覆盖。 */
    private Instant commitTime;

    /** push 前持久化的确定性 commit ID；空值表示尚未登记 commit。 */
    private String commitId;

    /** 调用方提供的 Tool 准备幂等键，按工作区唯一。 */
    private String prepareRequestKey;

    /** 准备请求内容摘要，同一幂等键不能用于不同内容。 */
    private String requestDigest;

    /** 稳定脱敏错误码，不保存 Git stderr。 */
    private String errorCode;

    /** CAS 单调版本；重复按钮只接受已完成转换的原版本。 */
    private Long version;
}
