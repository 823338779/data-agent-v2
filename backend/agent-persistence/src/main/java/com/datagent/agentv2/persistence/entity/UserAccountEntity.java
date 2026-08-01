package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_account")
public final class UserAccountEntity extends TenantAuditedEntity {

    private String username;
    private String passwordHash;
    private String status;

    @Version private Long version;
}
