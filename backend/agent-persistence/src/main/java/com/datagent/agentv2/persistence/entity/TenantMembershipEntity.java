package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tenant_membership")
public final class TenantMembershipEntity extends TenantAuditedEntity {

    private String userId;
    private String role;

    @Version private Long version;
}
