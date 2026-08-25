package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_account")
public final class UserAccountEntity extends TenantAuditedEntity {

    /** 用户账号的业务唯一标识，对应数据库 user_id 列。 */
    private String userId;

    private String username;
    private String passwordHash;

    /** 用户账号启停状态。 */
    private ActivationStatus status;

    @Version private Long version;
}
