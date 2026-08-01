package com.datagent.agentv2.auth.application;

import com.datagent.agentv2.auth.config.AuthProperties;
import com.datagent.agentv2.persistence.auth.TenantEntity;
import com.datagent.agentv2.persistence.auth.TenantMembershipEntity;
import com.datagent.agentv2.persistence.auth.TenantMembershipService;
import com.datagent.agentv2.persistence.auth.TenantService;
import com.datagent.agentv2.persistence.auth.UserAccountEntity;
import com.datagent.agentv2.persistence.auth.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "data-agent.auth.bootstrap.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LocalAdminBootstrap implements ApplicationRunner {

    private final TenantService tenants;
    private final UserAccountService userAccounts;
    private final TenantMembershipService memberships;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public LocalAdminBootstrap(
            TenantService tenants,
            UserAccountService userAccounts,
            TenantMembershipService memberships,
            PasswordEncoder passwordEncoder,
            AuthProperties properties) {
        this.tenants = tenants;
        this.userAccounts = userAccounts;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.Bootstrap bootstrap = properties.getBootstrap();
        ensureTenant(bootstrap);
        TenantContext.run(
                bootstrap.getTenantId(),
                () -> {
                    ensureUser(bootstrap);
                    ensureMembership(bootstrap);
                });
    }

    private void ensureTenant(AuthProperties.Bootstrap bootstrap) {
        if (tenants.getById(bootstrap.getTenantId()) != null) {
            return;
        }
        TenantEntity tenant = new TenantEntity();
        tenant.setId(bootstrap.getTenantId());
        tenant.setName(bootstrap.getTenantName());
        tenant.setStatus("active");
        tenant.setVersion(0L);
        saveIfStillAbsent(() -> tenants.save(tenant));
    }

    private void ensureUser(AuthProperties.Bootstrap bootstrap) {
        if (userAccounts.getById(bootstrap.getUserId()) != null) {
            return;
        }
        UserAccountEntity account = new UserAccountEntity();
        account.setId(bootstrap.getUserId());
        account.setUsername(bootstrap.getUsername());
        account.setPasswordHash(passwordEncoder.encode(bootstrap.getPassword()));
        account.setStatus("active");
        account.setVersion(0L);
        saveIfStillAbsent(() -> userAccounts.save(account));
    }

    private void ensureMembership(AuthProperties.Bootstrap bootstrap) {
        if (memberships.getById(bootstrap.getMembershipId()) != null) {
            return;
        }
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setId(bootstrap.getMembershipId());
        membership.setUserId(bootstrap.getUserId());
        membership.setRole("ADMIN");
        membership.setVersion(0L);
        saveIfStillAbsent(() -> memberships.save(membership));
    }

    private static void saveIfStillAbsent(Runnable save) {
        try {
            save.run();
        } catch (DuplicateKeyException concurrentBootstrap) {
            // Another instance completed the same fixed local bootstrap row first.
        }
    }
}
