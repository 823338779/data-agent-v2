package com.datagent.agentv2.persistence.entity.development;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** DevelopmentBuildAttempt 的持久状态；数据库操作仅由 persistence 服务负责。 */
@Getter
@Setter
@TableName("development_build_attempt")
public final class DevelopmentBuildAttemptEntity extends TenantAuditedEntity {
    /** 所属持久动作。 */
    private String operationId;

    /** 动作内构建尝试序号。 */
    private Integer attemptNumber;

    /** dbt-service 构建 UUID。 */
    private String buildId;

    /** 该尝试固定源码 SHA。 */
    private String commitSha;

    /** PREVIEW 或 PRODUCTION。 */
    private String environment;

    /** 原请求重试键。 */
    private String idempotencyKey;
}
