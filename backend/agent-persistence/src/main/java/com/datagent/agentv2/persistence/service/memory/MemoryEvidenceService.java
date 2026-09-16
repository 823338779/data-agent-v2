package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.persistence.entity.memory.MemoryEvidenceEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemoryEvidenceMapper;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 活动记忆证据关联边界；只保存来源元数据，绝不复制 evidence.content。 */
@Service
@RequiredArgsConstructor
public class MemoryEvidenceService extends ServiceImpl<MemoryEvidenceMapper, MemoryEvidenceEntity> {
    private final MemoryPersistenceMapper models;

    public void append(String memoryId, MemoryEvidence evidence) {
        var entity = models.toEntity(evidence);
        entity.setMemoryId(memoryId);
        save(entity);
    }

    public void deleteByMemoryId(String memoryId) {
        remove(
                Wrappers.lambdaQuery(MemoryEvidenceEntity.class)
                        .eq(MemoryEvidenceEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryEvidenceEntity::getMemoryId, memoryId));
    }
}
