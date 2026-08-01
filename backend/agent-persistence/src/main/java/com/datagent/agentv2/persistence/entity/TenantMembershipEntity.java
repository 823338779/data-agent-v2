package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tenant_membership")
public final class TenantMembershipEntity extends TenantAuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;
    private String role;

    @Version private Long version;
}
