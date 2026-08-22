package com.datagent.agentv2.persistence;

import com.datagent.agentv2.persistence.entity.TenantEntity;
import com.datagent.agentv2.persistence.entity.UserAccountEntity;
import com.datagent.agentv2.persistence.service.TenantService;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** 为当前固定身份创建业务表外键所需的最小数据库记录。 */
@Component
@RequiredArgsConstructor
public final class FixedIdentityBootstrap implements ApplicationRunner {

    private static final String TENANT_NAME = "Local";
    private static final String ACTIVE_STATUS = "active";
    private static final String UNUSED_PASSWORD_HASH = "not-used";

    private final TenantService tenants;
    private final UserAccountService users;

    @Override
    public void run(ApplicationArguments args) {
        // 先建立固定租户，确保固定用户及后续业务记录的外键可写入。
        if (tenants.findByUuid(TenantContext.TENANT_ID) == null) {
            TenantEntity tenant = new TenantEntity();
            tenant.setTenantId(TenantContext.TENANT_ID);
            tenant.setName(TENANT_NAME);
            tenant.setStatus(ACTIVE_STATUS);
            tenant.setVersion(0L);
            saveIfStillAbsent(() -> tenants.save(tenant));
        }

        // 用户身份直接来自代码常量；数据库行仅承载现有业务外键和并发锁。
        if (users.findByUuid(OperatorContext.USER_ID) == null) {
            UserAccountEntity user = new UserAccountEntity();
            user.setUserId(OperatorContext.USER_ID);
            user.setUsername(OperatorContext.SUBJECT);
            user.setPasswordHash(UNUSED_PASSWORD_HASH);
            user.setStatus(ACTIVE_STATUS);
            user.setVersion(0L);
            saveIfStillAbsent(() -> users.save(user));
        }
    }

    private static void saveIfStillAbsent(Runnable save) {
        try {
            save.run();
        } catch (DuplicateKeyException concurrentBootstrap) {
            // 另一个实例已完成同一固定记录的初始化。
        }
    }
}
