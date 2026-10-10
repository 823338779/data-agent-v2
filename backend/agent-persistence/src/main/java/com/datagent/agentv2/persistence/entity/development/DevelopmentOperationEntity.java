package com.datagent.agentv2.persistence.entity.development;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** DevelopmentOperation 的持久状态；数据库操作仅由 persistence 服务负责。 */
@Getter
@Setter
@TableName("development_operation")
public final class DevelopmentOperationEntity extends TenantAuditedEntity {
    /** 持久动作 UUID。 */
    private String operationId;

    /** 动作归属会话；分支创建时使用项目操作域。 */
    private String sessionId;

    /** Agent 项目归属。 */
    private String projectId;

    /** SAVE/PREVIEW/DELIVER/CREATE_BRANCH。 */
    private String type;

    /** 调用方动作重试键。 */
    private String idempotencyKey;

    /** 不可变完整请求摘要。 */
    private String requestDigest;

    /** 动作确认输入，不保存文件正文。 */
    private String requestJson;

    /** 受理时仓库分支及配置快照。 */
    private String contextJson;

    /** 持久阶段，先记录后外部 I/O。 */
    private String phase;

    /** PENDING/RUNNING/SUCCEEDED/BLOCKED/RESULT_UNKNOWN/FAILED。 */
    private String status;

    /** 操作恢复及状态推进 CAS 版本。 */
    private Long version;

    /** 外部写入前已固定的候选提交。 */
    private String candidateSha;

    /** 保存候选的远端父提交；恢复时只可在该父提交上重推原候选。 */
    private String candidateParentSha;

    /** 尚未核实推送的合并候选所在服务端临时目录。 */
    private String candidateStoragePath;

    /** 核实后的保存提交。 */
    private String pushedSha;

    /** 核实后的实际合并提交 M。 */
    private String mergeSha;

    /** 当前构建尝试身份。 */
    private String buildId;

    /** 构建显式重试序号，派生稳定幂等键。 */
    private Integer attemptNumber;

    /** 脱敏失败或阻塞原因。 */
    private String errorCode;

    /** 公平恢复调度的最近扫描时间。 */
    private java.time.LocalDateTime lastCheckedAt;
}
