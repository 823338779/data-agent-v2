package com.datagent.agentv2.context.lsm.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmContextManifestMapper;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmSummarySegmentMapper;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.platform.context.TenantContext;
import org.junit.jupiter.api.Test;

class LsmRepositoryIntegrationTest {

    private static final String TENANT_ID = "tenant-a";
    private static final String SESSION_ID = "session-a";

    @Test
    void manifestReadsAreAlwaysTenantScoped() {
        LsmContextManifestMapper mapper = mock(LsmContextManifestMapper.class);
        LsmContextManifestEntity manifest = new LsmContextManifestEntity();
        when(mapper.selectManifest(TENANT_ID, SESSION_ID)).thenReturn(manifest);
        LsmContextManifestRepository repository = new LsmContextManifestRepository(mapper);

        assertThat(TenantContext.call(TENANT_ID, () -> repository.findManifest(SESSION_ID)))
                .containsSame(manifest);
        verify(mapper).selectManifest(TENANT_ID, SESSION_ID);
    }

    @Test
    void staleManifestVersionOrGenerationCannotBeReplaced() {
        LsmContextManifestMapper mapper = mock(LsmContextManifestMapper.class);
        LsmContextManifestEntity replacement = new LsmContextManifestEntity();
        when(mapper.compareAndSet(TENANT_ID, SESSION_ID, 7L, 3L, replacement)).thenReturn(0);
        LsmContextManifestRepository repository = new LsmContextManifestRepository(mapper);

        boolean replaced =
                TenantContext.call(
                        TENANT_ID,
                        () -> repository.compareAndSetManifest(SESSION_ID, 7L, 3L, replacement));

        assertThat(replaced).isFalse();
        verify(mapper).compareAndSet(TENANT_ID, SESSION_ID, 7L, 3L, replacement);
    }

    @Test
    void segmentKeyIdempotencyReturnsTheFirstPersistedSegment() {
        LsmSummarySegmentMapper mapper = mock(LsmSummarySegmentMapper.class);
        LsmSummarySegmentEntity candidate = new LsmSummarySegmentEntity();
        candidate.setConversationSessionId(SESSION_ID);
        candidate.setSegmentKey("segment-key");
        LsmSummarySegmentEntity persisted = new LsmSummarySegmentEntity();
        when(mapper.selectBySegmentKey(TENANT_ID, SESSION_ID, "segment-key"))
                .thenReturn(null, persisted);
        LsmSummarySegmentRepository repository = new LsmSummarySegmentRepository(mapper);

        assertThat(TenantContext.call(TENANT_ID, () -> repository.findOrCreate(candidate)))
                .isSameAs(persisted);
        verify(mapper).insertIgnore(candidate);
        assertThat(candidate.getTenantId()).isEqualTo(TENANT_ID);
    }
}
