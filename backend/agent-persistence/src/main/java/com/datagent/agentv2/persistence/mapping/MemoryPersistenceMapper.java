package com.datagent.agentv2.persistence.mapping;

import com.datagent.agentv2.memory.MemoryEntry;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemoryReviewJob;
import com.datagent.agentv2.memory.MemoryReviewLease;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.memory.MemorySuppression;
import com.datagent.agentv2.persistence.entity.memory.MemoryEntryEntity;
import com.datagent.agentv2.persistence.entity.memory.MemoryEvidenceEntity;
import com.datagent.agentv2.persistence.entity.memory.MemoryReviewJobEntity;
import com.datagent.agentv2.persistence.entity.memory.MemoryReviewLeaseEntity;
import com.datagent.agentv2.persistence.entity.memory.MemoryStateEntity;
import com.datagent.agentv2.persistence.entity.memory.MemorySuppressionEntity;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.ReportingPolicy;

/** 记忆领域记录与物理行的纯转换；身份和审计字段由工厂及持久化框架负责。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MemoryPersistenceMapper {
    @Mapping(
            target = "kind",
            expression = "java(com.datagent.agentv2.memory.MemoryKind.parse(entity.getKind()))")
    @Mapping(
            target = "source",
            expression =
                    "java(com.datagent.agentv2.memory.MemorySource.valueOf(entity.getSource().toUpperCase(java.util.Locale.ROOT)))")
    MemoryEntry toModel(MemoryEntryEntity entity);

    @BeanMapping(ignoreUnmappedSourceProperties = {})
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "kind", expression = "java(model.kind().value())")
    @Mapping(
            target = "source",
            expression = "java(model.source().name().toLowerCase(java.util.Locale.ROOT))")
    MemoryEntryEntity toEntity(MemoryEntry model);

    MemoryState toModel(MemoryStateEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    MemoryStateEntity toEntity(MemoryState model);

    @BeanMapping(ignoreUnmappedSourceProperties = "content")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "memoryId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "evidenceCreatedAt", source = "model.createdAt")
    @Mapping(
            target = "kind",
            expression = "java(model.kind().name().toLowerCase(java.util.Locale.ROOT))")
    @Mapping(target = "promptVersion", source = "promptVersion")
    MemoryEvidenceEntity toEntity(MemoryEvidence model, String promptVersion);

    MemorySuppression toModel(MemorySuppressionEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    MemorySuppressionEntity toEntity(MemorySuppression model);

    @Mapping(
            target = "status",
            expression =
                    "java(com.datagent.agentv2.memory.MemoryReviewJob.Status.valueOf(entity.getStatus()))")
    MemoryReviewJob toModel(MemoryReviewJobEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "status", expression = "java(model.status().name())")
    MemoryReviewJobEntity toEntity(MemoryReviewJob model);

    MemoryReviewLease toModel(MemoryReviewLeaseEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    MemoryReviewLeaseEntity toEntity(MemoryReviewLease model);

    @ObjectFactory
    default MemoryEntryEntity newEntry() {
        return MemoryEntryEntity.create();
    }

    @ObjectFactory
    default MemoryStateEntity newState() {
        return MemoryStateEntity.create();
    }

    @ObjectFactory
    default MemoryEvidenceEntity newEvidence() {
        return MemoryEvidenceEntity.create();
    }

    @ObjectFactory
    default MemorySuppressionEntity newSuppression() {
        return MemorySuppressionEntity.create();
    }

    @ObjectFactory
    default MemoryReviewJobEntity newJob() {
        return MemoryReviewJobEntity.create();
    }

    @ObjectFactory
    default MemoryReviewLeaseEntity newLease() {
        return MemoryReviewLeaseEntity.create();
    }
}
