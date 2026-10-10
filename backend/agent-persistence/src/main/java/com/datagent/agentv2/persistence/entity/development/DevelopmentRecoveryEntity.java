package com.datagent.agentv2.persistence.entity.development;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 显式恢复请求回执，防止响应丢失后再次增加构建尝试。 */
@Getter
@Setter
@TableName("development_recovery")
public final class DevelopmentRecoveryEntity extends TenantAuditedEntity {
    /** 所属持久动作。 */
    private String operationId;

    /** 此次恢复的调用方幂等键。 */
    private String idempotencyKey;

    /** 原请求确认的操作版本，不随动作推进改变。 */
    private Long expectedVersion;
}
