package com.datagent.agentv2.context.lsm.persistence.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class LsmPersistenceStructMapperTest {

    private final LsmDocumentCodec documents = new LsmDocumentCodec();

    @Test
    void mapsPersistedSegmentWithoutDroppingSourceLineage() {
        LsmSummarySegmentEntity entity = new LsmSummarySegmentEntity();
        entity.setSummarySegmentId("segment-1");
        entity.setKind(LsmSummarySegment.SegmentKind.STANDARD.name());
        entity.setLevel(2);
        entity.setStartTurnSequence(11L);
        entity.setEndTurnSequence(20L);
        entity.setEstimatedTokenCount(120);
        entity.setSourceSegmentIds(documents.writeStringList(List.of("child-1", "child-2")));
        entity.setPartialSegment(true);

        LsmSummarySegment mapped =
                LsmPersistenceStructMapper.INSTANCE.toDomain(entity, documents);

        assertThat(mapped.sourceSegmentIds()).containsExactly("child-1", "child-2");
        assertThat(mapped.coverage()).isEqualTo(new LsmSummarySegment.Coverage(11L, 20L));
    }

    @Test
    void mapsPersistedManifestAndDerivesWarmRange() {
        LsmContextManifestEntity entity = new LsmContextManifestEntity();
        entity.setActiveRootSegmentId("root-1");
        entity.setActiveSegmentIds(documents.writeStringList(List.of("segment-1", "root-1")));
        entity.setPendingRawStartTurnSequence(21L);
        entity.setPendingRawEndTurnSequence(22L);
        entity.setWarmCutoffTurnSequence(120L);
        entity.setGeneration(4L);
        entity.setEffectiveFanout(3);
        entity.setEffectiveMaxLevel(2);

        LsmContextManifest mapped =
                LsmPersistenceStructMapper.INSTANCE.toDomain(entity, documents);

        assertThat(mapped.activeSegmentIds()).containsExactly("segment-1", "root-1");
        assertThat(mapped.warmStartTurnSequence()).isEqualTo(21L);
        assertThat(mapped.warmEndTurnSequence()).isEqualTo(120L);
    }
}
