package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemoryEntry;
import com.datagent.agentv2.persistence.entity.memory.MemoryEntryEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemoryEntryMapper;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 个人记忆正文的类型安全持久化边界；调用方不接触 Entity、Mapper 或 Wrapper。 */
@Service
@RequiredArgsConstructor
public class MemoryEntryService extends ServiceImpl<MemoryEntryMapper, MemoryEntryEntity> {
    private final MemoryPersistenceMapper models;

    public MemoryEntry find(String memoryId) {
        var entity =
                lambdaQuery()
                        .eq(MemoryEntryEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryEntryEntity::getMemoryId, memoryId)
                        .one();
        return entity == null ? null : models.toModel(entity);
    }

    public List<MemoryEntry> listCurrent() {
        return lambdaQuery()
                .eq(MemoryEntryEntity::getUserId, OperatorContext.requiredUserId())
                .orderByAsc(MemoryEntryEntity::getCreatedAt)
                .orderByAsc(MemoryEntryEntity::getMemoryId)
                .list()
                .stream()
                .map(models::toModel)
                .toList();
    }

    public MemoryEntry insert(MemoryEntry memory) {
        var entity = models.toEntity(memory);
        save(entity);
        return models.toModel(entity);
    }

    public boolean replace(MemoryEntry memory, long expectedVersion) {
        var replacement = models.toEntity(memory);
        return baseMapper.update(
                        replacement,
                        Wrappers.lambdaUpdate(MemoryEntryEntity.class)
                                .eq(MemoryEntryEntity::getUserId, OperatorContext.requiredUserId())
                                .eq(MemoryEntryEntity::getMemoryId, memory.memoryId())
                                .eq(MemoryEntryEntity::getVersion, expectedVersion))
                == 1;
    }

    public boolean deletePhysical(String memoryId, long expectedVersion) {
        return remove(
                Wrappers.lambdaQuery(MemoryEntryEntity.class)
                        .eq(MemoryEntryEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryEntryEntity::getMemoryId, memoryId)
                        .eq(MemoryEntryEntity::getVersion, expectedVersion));
    }
}
