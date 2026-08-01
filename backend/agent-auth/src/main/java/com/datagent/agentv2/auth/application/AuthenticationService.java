package com.datagent.agentv2.auth.application;

import com.datagent.agentv2.auth.config.AuthProperties;
import com.datagent.agentv2.auth.security.AuthenticatedUser;
import com.datagent.agentv2.persistence.entity.TenantMembershipEntity;
import com.datagent.agentv2.persistence.entity.UserAccountEntity;
import com.datagent.agentv2.persistence.service.TenantMembershipService;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserAccountService userAccounts;
    private final TenantMembershipService memberships;
    private final RedisLoginSessionStore loginSessions;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public Optional<LoginToken> login(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            return Optional.empty();
        }
        String tenantId = properties.getBootstrap().getTenantId();
        return TenantContext.call(
                tenantId,
                () ->
                        userAccounts
                                .lambdaQuery()
                                .eq(UserAccountEntity::getUsername, username)
                                .eq(UserAccountEntity::getStatus, "active")
                                .oneOpt()
                                .filter(
                                        account ->
                                                passwordEncoder.matches(
                                                        password, account.getPasswordHash()))
                                .map(this::createLoginToken));
    }

    @Transactional
    public Optional<AuthenticatedUser> authenticate(String token) {
        return findSession(token)
                .flatMap(
                        session ->
                                TenantContext.call(
                                        session.tenantId(), () -> activeUser(session.userId())));
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            loginSessions.delete(token);
        }
    }

    public Duration lifetime() {
        return properties.getSession().getLifetime();
    }

    private LoginToken createLoginToken(UserAccountEntity account) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        loginSessions.save(token, account.getTenantId(), account.getUuid(), lifetime());
        return new LoginToken(token, lifetime());
    }

    private Optional<RedisLoginSessionStore.LoginSession> findSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return loginSessions.find(token);
    }

    private Optional<AuthenticatedUser> activeUser(String userId) {
        UserAccountEntity account = userAccounts.findByUuid(userId);
        if (account == null || !"active".equals(account.getStatus())) {
            return Optional.empty();
        }
        Set<String> roles =
                memberships
                        .lambdaQuery()
                        .eq(TenantMembershipEntity::getUserId, userId)
                        .list()
                        .stream()
                        .map(TenantMembershipEntity::getRole)
                        .map(AuthenticationService::externalRole)
                        .collect(Collectors.toUnmodifiableSet());
        return Optional.of(
                new AuthenticatedUser(
                        account.getTenantId(), account.getUuid(), account.getUsername(), roles));
    }

    private static String externalRole(String role) {
        return switch (role) {
            case "ADMIN" -> "TENANT_ADMIN";
            case "USER" -> "TENANT_VIEWER";
            default -> role;
        };
    }

    public record LoginToken(String value, Duration lifetime) {}
}
