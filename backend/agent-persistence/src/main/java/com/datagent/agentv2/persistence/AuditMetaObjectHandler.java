package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class AuditMetaObjectHandler implements MetaObjectHandler {

    private final Clock clock;

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = clock.instant();
        strictInsertFill(metaObject, "createdAt", Instant.class, now);
        strictInsertFill(metaObject, "updatedAt", Instant.class, now);
        if (metaObject.hasSetter("tenantId")) {
            strictInsertFill(
                    metaObject, "tenantId", String.class, TenantContext.requiredTenantId());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Instant now = clock.instant();
        strictUpdateFill(metaObject, "updatedAt", Instant.class, now);
    }
}
