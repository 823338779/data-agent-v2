package com.datagent.agentv2.context.lsm.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmContextManifestMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** 统一持久化模块中的 manifest 存储边界；版本与代次共同阻止旧视图覆盖新发布。 */
@Repository
@ConditionalOnProperty(prefix = "data-agent.context", name = "strategy", havingValue = "lsm")
public class LsmContextManifestRepository
        extends ServiceImpl<LsmContextManifestMapper, LsmContextManifestEntity> {
    private static final String JSON_MAPPING =
            "jdbcType=OTHER,typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler";

    /** 读取当前租户会话唯一未删除视图。 */
    public Optional<LsmContextManifestEntity> findManifest(String sessionId) {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(LsmContextManifestEntity::getTenantId, TenantContext.requiredTenantId())
                        .eq(LsmContextManifestEntity::getConversationSessionId, sessionId)
                        .eq(LsmContextManifestEntity::isDeleted, false)
                        .one());
    }

    /** 数据库唯一键收敛并发初始化，不捕获会破坏当前事务的唯一键异常。 */
    public LsmContextManifestEntity findOrCreate(LsmContextManifestEntity candidate) {
        candidate.setTenantId(TenantContext.requiredTenantId());
        baseMapper.insertIgnore(candidate);
        return findManifest(candidate.getConversationSessionId())
                .orElseThrow(() -> new IllegalStateException("LSM manifest was not persisted"));
    }

    /** 显式 SET 可空指针，避免空值更新策略保留上一代范围；只改发布相关字段。 */
    public boolean compareAndSetManifest(
            String sessionId,
            long expectedVersion,
            long expectedGeneration,
            LsmContextManifestEntity replacement) {
        return baseMapper.update(
                        null,
                        Wrappers.lambdaUpdate(LsmContextManifestEntity.class)
                                .eq(
                                        LsmContextManifestEntity::getTenantId,
                                        TenantContext.requiredTenantId())
                                .eq(LsmContextManifestEntity::getConversationSessionId, sessionId)
                                .eq(LsmContextManifestEntity::getVersion, expectedVersion)
                                .eq(LsmContextManifestEntity::getGeneration, expectedGeneration)
                                .eq(LsmContextManifestEntity::isDeleted, false)
                                .set(
                                        LsmContextManifestEntity::getActiveRootSegmentId,
                                        replacement.getActiveRootSegmentId())
                                .set(
                                        LsmContextManifestEntity::getActiveSegmentIds,
                                        replacement.getActiveSegmentIds(),
                                        JSON_MAPPING)
                                .set(
                                        LsmContextManifestEntity::getPendingRawStartTurnSequence,
                                        replacement.getPendingRawStartTurnSequence())
                                .set(
                                        LsmContextManifestEntity::getPendingRawEndTurnSequence,
                                        replacement.getPendingRawEndTurnSequence())
                                .set(
                                        LsmContextManifestEntity::getHotFromTurnSequence,
                                        replacement.getHotFromTurnSequence())
                                .set(
                                        LsmContextManifestEntity::getEvaluatedUpToMessageSequence,
                                        replacement.getEvaluatedUpToMessageSequence())
                                .set(
                                        LsmContextManifestEntity::getEffectivePolicyVersion,
                                        replacement.getEffectivePolicyVersion())
                                .set(
                                        LsmContextManifestEntity::getEffectiveFanout,
                                        replacement.getEffectiveFanout())
                                .set(
                                        LsmContextManifestEntity::getEffectiveMaxLevel,
                                        replacement.getEffectiveMaxLevel())
                                .set(
                                        LsmContextManifestEntity::getGeneration,
                                        replacement.getGeneration())
                                .set(
                                        LsmContextManifestEntity::getUpdatedAt,
                                        replacement.getUpdatedAt())
                                .set(
                                        LsmContextManifestEntity::getUpdatedBy,
                                        replacement.getUpdatedBy())
                                .setIncrBy(LsmContextManifestEntity::getVersion, 1))
                == 1;
    }
}
