package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapping.HierarchicalPersistenceMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;

/** 封装唯一 checkpoint 的幂等创建与 CAS；版本、代次和已评估边界必须共同匹配。 */
@RequiredArgsConstructor
public class HierarchicalCheckpointRepository
        extends ServiceImpl<HierarchicalCheckpointMapper, HierarchicalCheckpointEntity> {
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private final HierarchicalPersistenceMapper models;

    /** 不存在时返回 null，由策略首次访问时初始化。 */
    public HierarchicalCheckpoint findCheckpoint(String sessionId) {
        return models.toDomain(
                lambdaQuery()
                        .eq(
                                HierarchicalCheckpointEntity::getTenantId,
                                TenantContext.requiredTenantId())
                        .eq(HierarchicalCheckpointEntity::getConversationSessionId, sessionId)
                        .eq(HierarchicalCheckpointEntity::isDeleted, false)
                        .one());
    }

    public HierarchicalCheckpoint create(HierarchicalCheckpoint candidate) {
        var entity = models.toEntity(candidate, TenantContext.requiredTenantId());
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        baseMapper.insertIgnore(entity);
        var persisted = findCheckpoint(candidate.conversationSessionId());
        if (persisted == null) {
            throw new IllegalStateException("Hierarchical checkpoint was not persisted");
        }
        return persisted;
    }

    public boolean compareAndSetCheckpoint(
            HierarchicalCheckpoint expected, HierarchicalCheckpoint replacement) {
        if (!expected.conversationSessionId().equals(replacement.conversationSessionId())) {
            throw new IllegalArgumentException(
                    "Hierarchical checkpoint execution boundary mismatch");
        }
        if (!expected.checkpointId().equals(replacement.checkpointId())
                || replacement.strategyGeneration() != expected.strategyGeneration() + 1L) {
            return false;
        }
        // V3 的覆盖位置不能随模型摘要倒退，且升级后的记录不允许旧算法继续写入。
        if (replacement.schemaVersion() < expected.schemaVersion()
                || (expected.coveredThrough() != null
                        && (replacement.coveredThrough().compareTo(expected.coveredThrough()) < 0
                                || replacement
                                                .evaluatedThrough()
                                                .compareTo(expected.evaluatedThrough())
                                        < 0))) {
            return false;
        }
        // 单条条件 UPDATE 同时验证读取快照和单调边界；不能让旧执行倒退已发布的摘要范围。
        return baseMapper.update(
                        null,
                        Wrappers.lambdaUpdate(HierarchicalCheckpointEntity.class)
                                .eq(
                                        HierarchicalCheckpointEntity::getTenantId,
                                        TenantContext.requiredTenantId())
                                .eq(
                                        HierarchicalCheckpointEntity::getConversationSessionId,
                                        expected.conversationSessionId())
                                .eq(HierarchicalCheckpointEntity::getVersion, expected.version())
                                .eq(
                                        HierarchicalCheckpointEntity::getStrategyGeneration,
                                        expected.strategyGeneration())
                                .eq(
                                        HierarchicalCheckpointEntity
                                                ::getEvaluatedUpToConversationMessageSequence,
                                        expected.evaluatedUpToConversationMessageSequence())
                                .le(
                                        HierarchicalCheckpointEntity
                                                ::getEvaluatedUpToConversationMessageSequence,
                                        replacement.evaluatedUpToConversationMessageSequence())
                                .lt(
                                        HierarchicalCheckpointEntity::getStrategyGeneration,
                                        replacement.strategyGeneration())
                                .eq(HierarchicalCheckpointEntity::isDeleted, false)
                                .set(
                                        HierarchicalCheckpointEntity::getActiveColdId,
                                        replacement.activeColdId())
                                .set(
                                        HierarchicalCheckpointEntity
                                                ::getEvaluatedUpToConversationMessageSequence,
                                        replacement.evaluatedUpToConversationMessageSequence())
                                .set(
                                        HierarchicalCheckpointEntity::getStrategyGeneration,
                                        replacement.strategyGeneration())
                                .set(
                                        HierarchicalCheckpointEntity::getUpdatedAt,
                                        LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                                .set(HierarchicalCheckpointEntity::getUpdatedBy, SYSTEM_OPERATOR)
                                .set(
                                        HierarchicalCheckpointEntity::getCoveredThroughJson,
                                        models.writeCursor(replacement.coveredThrough()))
                                .set(
                                        HierarchicalCheckpointEntity::getEvaluatedThroughJson,
                                        models.writeCursor(replacement.evaluatedThrough()))
                                .set(
                                        HierarchicalCheckpointEntity::getPolicyVersion,
                                        replacement.policyVersion())
                                .set(
                                        HierarchicalCheckpointEntity::getSchemaVersion,
                                        replacement.schemaVersion())
                                .setIncrBy(HierarchicalCheckpointEntity::getVersion, 1))
                == 1;
    }
}
