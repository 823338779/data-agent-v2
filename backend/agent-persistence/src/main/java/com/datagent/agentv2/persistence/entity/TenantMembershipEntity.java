package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tenant_membership")
public final class TenantMembershipEntity extends TenantAuditedEntity {

    /** 租户成员关系的业务唯一标识，对应数据库 membership_id 列。 */
    private String membershipId;

    private String userId;
    private String role;

    @Version private Long version;
}
