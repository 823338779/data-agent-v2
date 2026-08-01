package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class TenantAuditedEntity extends AuditedEntity {

    @TableField(value = "tenant_id", fill = FieldFill.INSERT)
    private String tenantId;
}
