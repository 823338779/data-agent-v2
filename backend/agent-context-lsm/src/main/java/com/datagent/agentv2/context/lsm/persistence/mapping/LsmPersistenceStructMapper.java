package com.datagent.agentv2.context.lsm.persistence.mapping;

import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import java.util.List;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** LSM 持久化 Entity 与规划层不可变数据之间的结构映射。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LsmPersistenceStructMapper {

    LsmPersistenceStructMapper INSTANCE = Mappers.getMapper(LsmPersistenceStructMapper.class);

    @Mapping(target = "segmentId", source = "summarySegmentId")
    @Mapping(target = "coverage", expression = "java(toCoverage(entity))")
    LsmSummarySegment toDomain(LsmSummarySegmentEntity entity, @Context LsmDocumentCodec documents);

    @Mapping(target = "warmStartTurnSequence", expression = "java(warmStart(entity))")
    @Mapping(target = "warmEndTurnSequence", source = "warmCutoffTurnSequence")
    LsmContextManifest toDomain(
            LsmContextManifestEntity entity, @Context LsmDocumentCodec documents);

    default List<String> readStringList(String document, @Context LsmDocumentCodec documents) {
        return document == null ? List.of() : documents.readStringList(document);
    }

    default LsmSummarySegment.Coverage toCoverage(LsmSummarySegmentEntity entity) {
        return new LsmSummarySegment.Coverage(
                entity.getStartTurnSequence(), entity.getEndTurnSequence());
    }

    default Long warmStart(LsmContextManifestEntity entity) {
        Long warmEnd = entity.getWarmCutoffTurnSequence();
        if (warmEnd == null || warmEnd == 0L) {
            return null;
        }
        return Math.max(1L, warmEnd - (100L + Math.floorMod(warmEnd - 100L, 10L)) + 1L);
    }
}
