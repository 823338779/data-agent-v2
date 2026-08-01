package com.datagent.agentv2.auth.application;

import com.datagent.agentv2.auth.config.AuthProperties;
import com.datagent.agentv2.auth.security.AuthenticatedUser;
import com.datagent.agentv2.persistence.entity.LoginSessionEntity;
import com.datagent.agentv2.persistence.service.LoginSessionService;
import com.datagent.agentv2.persistence.entity.TenantMembershipEntity;
import com.datagent.agentv2.persistence.service.TenantMembershipService;
import com.datagent.agentv2.persistence.entity.UserAccountEntity;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private final UserAccountService userAccounts;
    private final TenantMembershipService memberships;
    private final LoginSessionService loginSessions;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthenticationService(
            UserAccountService userAccounts,
            TenantMembershipService memberships,
            LoginSessionService loginSessions,
            PasswordEncoder passwordEncoder,
            IdGenerator idGenerator,
            AuthProperties properties,
            Clock clock) {
        this.userAccounts = userAccounts;
        this.memberships = memberships;
        this.loginSessions = loginSessions;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.clock = clock;
    }

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
        return findSession(token, true)
                .flatMap(
                        session ->
                                TenantContext.call(
                                        session.getTenantId(),
                                        () -> {
                                            Optional<AuthenticatedUser> user =
                                                    activeUser(session.getUserId());
                                            if (user.isPresent()) {
                                                session.setLastSeenAt(clock.instant());
                                                loginSessions.updateById(session);
                                            }
                                            return user;
                                        }));
    }

    @Transactional
    public void logout(String token) {
        findSession(token, false)
                .ifPresent(
                        session ->
                                TenantContext.run(
                                        session.getTenantId(),
                                        () -> {
                                            session.setRevokedAt(clock.instant());
                                            loginSessions.updateById(session);
                                        }));
    }

    public Duration lifetime() {
        return properties.getSession().getLifetime();
    }

    private LoginToken createLoginToken(UserAccountEntity account) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant now = clock.instant();

        LoginSessionEntity session = new LoginSessionEntity();
        session.setId(idGenerator.next("login_session"));
        session.setUserId(account.getId());
        session.setTokenHash(hash(token));
        session.setExpiresAt(now.plus(lifetime()));
        session.setLastSeenAt(now);
        session.setVersion(0L);
        loginSessions.save(session);
        return new LoginToken(token, lifetime());
    }

    private Optional<LoginSessionEntity> findSession(String token, boolean requireUnexpired) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hash(token);
        return requireUnexpired
                ? loginSessions.findActiveByTokenHash(tokenHash, clock.instant())
                : loginSessions.findRevocableByTokenHash(tokenHash);
    }

    private Optional<AuthenticatedUser> activeUser(String userId) {
        UserAccountEntity account = userAccounts.getById(userId);
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
                        account.getTenantId(), account.getId(), account.getUsername(), roles));
    }

    private static String externalRole(String role) {
        return switch (role) {
            case "ADMIN" -> "TENANT_ADMIN";
            case "USER" -> "TENANT_VIEWER";
            default -> role;
        };
    }

    private static String hash(String token) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record LoginToken(String value, Duration lifetime) {}
}
