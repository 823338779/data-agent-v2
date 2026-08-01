package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
public final class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // 使用服务端本地时间统一填充新增记录的审计时间。
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        // 仅租户隔离实体从上下文填充 tenantId；TenantEntity.tenantId 是自身业务标识。
        if (tenantAuditedEntity(metaObject) != null) {
            strictInsertFill(
                    metaObject, "tenantId", String.class, TenantContext.requiredTenantId());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 使用服务端本地时间刷新记录的最后更新时间。
        LocalDateTime now = LocalDateTime.now();
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    private static TenantAuditedEntity tenantAuditedEntity(MetaObject metaObject) {
        if (metaObject.getOriginalObject() instanceof TenantAuditedEntity entity) {
            return entity;
        }
        if (metaObject.hasGetter("et")
                && metaObject.getValue("et") instanceof TenantAuditedEntity entity) {
            return entity;
        }
        return null;
    }
}
