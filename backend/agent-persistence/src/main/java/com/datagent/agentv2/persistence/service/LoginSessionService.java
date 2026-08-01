package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.datagent.agentv2.persistence.AuditedServiceImpl;
import java.time.Instant;
import java.util.Optional;

import com.datagent.agentv2.persistence.entity.LoginSessionEntity;
import com.datagent.agentv2.persistence.service.mapper.LoginSessionMapper;
import org.springframework.stereotype.Service;

@Service
public class LoginSessionService
        extends AuditedServiceImpl<LoginSessionMapper, LoginSessionEntity> {

    private static final IgnoreStrategy IGNORE_TENANT =
            IgnoreStrategy.builder().tenantLine(true).build();

    public Optional<LoginSessionEntity> findActiveByTokenHash(String tokenHash, Instant now) {
        return InterceptorIgnoreHelper.execute(
                IGNORE_TENANT,
                () ->
                        lambdaQuery()
                                .eq(LoginSessionEntity::getTokenHash, tokenHash)
                                .isNull(LoginSessionEntity::getRevokedAt)
                                .gt(LoginSessionEntity::getExpiresAt, now)
                                .oneOpt());
    }

    public Optional<LoginSessionEntity> findRevocableByTokenHash(String tokenHash) {
        return InterceptorIgnoreHelper.execute(
                IGNORE_TENANT,
                () ->
                        lambdaQuery()
                                .eq(LoginSessionEntity::getTokenHash, tokenHash)
                                .isNull(LoginSessionEntity::getRevokedAt)
                                .oneOpt());
    }
}
