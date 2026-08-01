package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

/** 持久化租户与用户之间的角色成员关系。 */
@Getter
@Setter
@TableName("tenant_membership")
public final class TenantMembershipEntity extends TenantAuditedEntity {

    /** 租户成员关系的业务唯一标识，对应数据库 tenant_membership_id 列。 */
    private String tenantMembershipId;

    /** 成员关系所关联的用户业务标识。 */
    private String userId;

    /** 用户在该租户内拥有的授权角色。 */
    private String role;

    /** 乐观锁版本，防止成员角色被并发覆盖。 */
    @Version private Long version;
}
