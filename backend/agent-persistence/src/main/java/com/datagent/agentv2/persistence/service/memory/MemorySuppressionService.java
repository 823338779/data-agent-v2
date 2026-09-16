package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemorySuppression;
import com.datagent.agentv2.persistence.entity.memory.MemorySuppressionEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemorySuppressionMapper;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 删除抑制指纹的持久化边界。 */
@Service
@RequiredArgsConstructor
public class MemorySuppressionService
        extends ServiceImpl<MemorySuppressionMapper, MemorySuppressionEntity> {
    private final MemoryPersistenceMapper models;

    public MemorySuppression find(String normalizedHash) {
        var entity =
                lambdaQuery()
                        .eq(MemorySuppressionEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemorySuppressionEntity::getNormalizedHash, normalizedHash)
                        .one();
        return entity == null ? null : models.toModel(entity);
    }

    public MemorySuppression insert(MemorySuppression suppression) {
        var entity = models.toEntity(suppression);
        save(entity);
        return models.toModel(entity);
    }

    public boolean deleteByHash(String normalizedHash) {
        return remove(
                Wrappers.lambdaQuery(MemorySuppressionEntity.class)
                        .eq(MemorySuppressionEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemorySuppressionEntity::getNormalizedHash, normalizedHash));
    }
}
