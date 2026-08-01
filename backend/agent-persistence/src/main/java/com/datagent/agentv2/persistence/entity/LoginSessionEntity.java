package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("login_session")
public final class LoginSessionEntity extends TenantAuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant lastSeenAt;

    @Version private Long version;
}
