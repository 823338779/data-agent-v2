package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalColdEntity;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalColdMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapping.HierarchicalPersistenceMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

/** 统一持久化模块内的不可变 Cold 存储；同一摘要身份并发写入时返回首个成功版本。 */
@RequiredArgsConstructor
public class HierarchicalColdRepository
        extends ServiceImpl<HierarchicalColdMapper, HierarchicalColdEntity> {
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private final HierarchicalPersistenceMapper models;

    public HierarchicalCold insertOrSelect(HierarchicalCold cold) {
        var existing = find(cold.conversationSessionId(), cold.coldId());
        if (existing != null) {
            return existing;
        }
        var entity = models.toEntity(cold, TenantContext.requiredTenantId());
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setVersion(0L);
        baseMapper.insertIgnore(entity);
        var persisted = find(cold.conversationSessionId(), cold.coldId());
        if (persisted == null) {
            throw new IllegalStateException("Hierarchical Cold was not persisted");
        }
        return persisted;
    }

    /** 不存在时返回 null；始终按会话限定不可变摘要身份。 */
    public HierarchicalCold find(String sessionId, String coldId) {
        return models.toDomain(
                lambdaQuery()
                        .eq(HierarchicalColdEntity::getTenantId, TenantContext.requiredTenantId())
                        .eq(
                                HierarchicalColdEntity::getConversationSessionId,
                                Objects.requireNonNull(sessionId, "sessionId"))
                        .eq(
                                HierarchicalColdEntity::getHierarchicalColdId,
                                Objects.requireNonNull(coldId, "coldId"))
                        .eq(HierarchicalColdEntity::isDeleted, false)
                        .one());
    }
}
