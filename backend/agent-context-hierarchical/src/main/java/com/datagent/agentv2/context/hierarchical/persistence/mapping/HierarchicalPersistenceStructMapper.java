package com.datagent.agentv2.context.hierarchical.persistence.mapping;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCompactionJobEntity;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalSummaryNodeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** Hierarchical 领域状态与插件私有持久化 Entity 之间的结构映射。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface HierarchicalPersistenceStructMapper {

    HierarchicalPersistenceStructMapper INSTANCE =
            Mappers.getMapper(HierarchicalPersistenceStructMapper.class);

    @Mapping(target = "hierarchicalSummaryNodeId", source = "nodeId")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "version", ignore = true)
    HierarchicalSummaryNodeEntity toEntity(HierarchicalSummaryNode node);

    @Mapping(target = "nodeId", source = "hierarchicalSummaryNodeId")
    HierarchicalSummaryNode toDomain(HierarchicalSummaryNodeEntity entity);

    @Mapping(target = "hierarchicalCheckpointId", source = "checkpointId")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    HierarchicalCheckpointEntity toEntity(HierarchicalCheckpoint checkpoint);

    @Mapping(target = "checkpointId", source = "hierarchicalCheckpointId")
    HierarchicalCheckpoint toDomain(HierarchicalCheckpointEntity entity);

    @Mapping(target = "hierarchicalCompactionJobId", source = "jobId")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "candidateColdDocument", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    HierarchicalCompactionJobEntity toEntity(HierarchicalCompactionJob job);

    @Mapping(target = "jobId", source = "hierarchicalCompactionJobId")
    HierarchicalCompactionJob toDomain(HierarchicalCompactionJobEntity entity);
}
