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
        append(memoryId, evidence, null);
    }

    /** 返回是否首次追加；调用方仅在出现新证据时刷新后台确认时间。 */
    public boolean append(String memoryId, MemoryEvidence evidence, String promptVersion) {
        // 统一写入服务已持有用户状态锁；同一证据可被多个操作再次引用，但关联只保存一次。
        if (lambdaQuery()
                .eq(MemoryEvidenceEntity::getUserId, OperatorContext.requiredUserId())
                .eq(MemoryEvidenceEntity::getMemoryId, memoryId)
                .eq(
                        MemoryEvidenceEntity::getKind,
                        evidence.kind().name().toLowerCase(java.util.Locale.ROOT))
                .eq(MemoryEvidenceEntity::getEvidenceId, evidence.evidenceId())
                .exists()) {
            return false;
        }
        var entity = models.toEntity(evidence, promptVersion);
        entity.setMemoryId(memoryId);
        save(entity);
        return true;
    }

    public void deleteByMemoryId(String memoryId) {
        remove(
                Wrappers.lambdaQuery(MemoryEvidenceEntity.class)
                        .eq(MemoryEvidenceEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryEvidenceEntity::getMemoryId, memoryId));
    }
}
