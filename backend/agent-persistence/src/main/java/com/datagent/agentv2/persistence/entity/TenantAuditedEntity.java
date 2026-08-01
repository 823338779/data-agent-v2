package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.datagent.agentv2.persistence.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class TenantAuditedEntity extends AuditedEntity {

    @TableField(value = "tenant_id", fill = FieldFill.INSERT)
    private String tenantId;
}
