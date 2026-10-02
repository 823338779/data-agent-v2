package com.datagent.agentv2.context.hierarchical.persistence.mapping;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalColdEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 纯结构转换；创建审计时间与发布默认值由存储操作明确填充，不包含 I/O。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface HierarchicalPersistenceMapper {
    @Mapping(target = "hierarchicalColdId", source = "cold.coldId")
    @Mapping(target = "tenantId", source = "tenantId")
    @Mapping(target = "startTurnSequence", source = "cold.coverage.startTurnSequence")
    @Mapping(target = "endTurnSequence", source = "cold.coverage.endTurnSequence")
    @Mapping(target = "startMessageSequence", source = "cold.coverage.startMessageSequence")
    @Mapping(target = "endMessageSequence", source = "cold.coverage.endMessageSequence")
    HierarchicalColdEntity toEntity(HierarchicalCold cold, String tenantId);

    @Mapping(target = "coldId", source = "hierarchicalColdId")
    @Mapping(target = "coverage", expression = "java(coverage(entity))")
    HierarchicalCold toDomain(HierarchicalColdEntity entity);

    @Mapping(target = "hierarchicalCheckpointId", source = "checkpoint.checkpointId")
    @Mapping(target = "tenantId", source = "tenantId")
    HierarchicalCheckpointEntity toEntity(HierarchicalCheckpoint checkpoint, String tenantId);

    @Mapping(target = "checkpointId", source = "hierarchicalCheckpointId")
    HierarchicalCheckpoint toDomain(HierarchicalCheckpointEntity entity);

    default EventSummaryCoverage coverage(HierarchicalColdEntity entity) {
        return new EventSummaryCoverage(
                entity.getStartTurnSequence(),
                entity.getEndTurnSequence(),
                entity.getStartMessageSequence(),
                entity.getEndMessageSequence());
    }
}
