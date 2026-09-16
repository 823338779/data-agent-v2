package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryOperation;
import com.datagent.agentv2.memory.MemoryOutcome;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemoryStatus;
import com.datagent.agentv2.persistence.entity.memory.MemoryOperationEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemoryOperationMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import org.springframework.stereotype.Service;

/** 幂等操作结果边界；配额和版本保留提交时结果，重放仅由上层补充目标当前存在性。 */
@Service
public class MemoryOperationService
        extends ServiceImpl<MemoryOperationMapper, MemoryOperationEntity> {
    public MemoryOperation find(String requestId) {
        var entity =
                lambdaQuery()
                        .eq(MemoryOperationEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryOperationEntity::getRequestId, requestId)
                        .one();
        return entity == null ? null : toModel(entity);
    }

    public MemoryOperation insert(MemoryOperation operation) {
        var entity = MemoryOperationEntity.create();
        entity.setRequestId(operation.requestId());
        entity.setPayloadHash(operation.payloadHash());
        entity.setOutcomeStatus(operation.outcome().status().name());
        entity.setMemoryId(operation.outcome().memoryId());
        entity.setResultVersion(operation.outcome().version());
        entity.setResultRevision(operation.outcome().revision());
        entity.setCreated(operation.outcome().created());
        entity.setTargetExists(operation.outcome().targetExists());
        var profile = operation.outcome().quotas().get(MemoryKind.PERSON_PROFILE);
        var preference = operation.outcome().quotas().get(MemoryKind.COLLABORATION_PREFERENCE);
        entity.setPersonProfileUsed(profile == null ? null : profile.used());
        entity.setPersonProfileLimit(profile == null ? null : profile.limit());
        entity.setCollaborationPreferenceUsed(preference == null ? null : preference.used());
        entity.setCollaborationPreferenceLimit(preference == null ? null : preference.limit());
        entity.setCreatedAt(operation.createdAt());
        save(entity);
        return toModel(entity);
    }

    private static MemoryOperation toModel(MemoryOperationEntity entity) {
        var quotas = new java.util.EnumMap<MemoryKind, MemoryQuota>(MemoryKind.class);
        if (entity.getPersonProfileUsed() != null) {
            quotas.put(
                    MemoryKind.PERSON_PROFILE,
                    new MemoryQuota(entity.getPersonProfileUsed(), entity.getPersonProfileLimit()));
        }
        if (entity.getCollaborationPreferenceUsed() != null) {
            quotas.put(
                    MemoryKind.COLLABORATION_PREFERENCE,
                    new MemoryQuota(
                            entity.getCollaborationPreferenceUsed(),
                            entity.getCollaborationPreferenceLimit()));
        }
        var outcome =
                new MemoryOutcome(
                        MemoryStatus.valueOf(entity.getOutcomeStatus()),
                        entity.getMemoryId(),
                        entity.getResultVersion(),
                        entity.getResultRevision(),
                        entity.getCreated(),
                        entity.getTargetExists(),
                        quotas);
        return new MemoryOperation(
                entity.getRequestId(), entity.getPayloadHash(), outcome, entity.getCreatedAt());
    }
}
