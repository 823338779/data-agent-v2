package com.datagent.agentv2.context.lsm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionOutcome;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PublishResult;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import org.junit.jupiter.api.Test;

/** 验证协调器仅串联短事务与事务外生成，并对 CAS 冲突执行有界重规划。 */
class LsmCompactionCoordinatorTest {

    @Test
    void noPlanReturnsUnchangedWithoutGeneratingOrPublishing() {
        LsmCompactionPlanningTransaction planning = mock(LsmCompactionPlanningTransaction.class);
        LsmCompactionRecoveryService recovery = mock(LsmCompactionRecoveryService.class);
        LsmManifestPublicationTransaction publication =
                mock(LsmManifestPublicationTransaction.class);
        CompactionRequest request = mock(CompactionRequest.class);
        PlannedCompaction noop = PlannedCompaction.noop(request);
        when(planning.begin(request)).thenReturn(noop);

        CompactionOutcome outcome =
                new LsmCompactionCoordinator(planning, recovery, publication)
                        .compactIfRequired(request);

        assertThat(outcome.publishResult()).isEqualTo(PublishResult.UNCHANGED);
        verify(planning).begin(request);
    }

    @Test
    void casConflictReplansAtMostTwice() {
        LsmCompactionPlanningTransaction planning = mock(LsmCompactionPlanningTransaction.class);
        LsmCompactionRecoveryService recovery = mock(LsmCompactionRecoveryService.class);
        LsmManifestPublicationTransaction publication =
                mock(LsmManifestPublicationTransaction.class);
        CompactionRequest request = mock(CompactionRequest.class);
        PlannedCompaction first = mock(PlannedCompaction.class);
        PlannedCompaction second = mock(PlannedCompaction.class);
        PlannedCompaction third = mock(PlannedCompaction.class);
        ReadyCompaction firstReady = mock(ReadyCompaction.class);
        ReadyCompaction secondReady = mock(ReadyCompaction.class);
        ReadyCompaction thirdReady = mock(ReadyCompaction.class);
        when(planning.begin(request)).thenReturn(first, second, third);
        when(request.replanProvider()).thenReturn(superseded -> request);
        when(recovery.resume(first)).thenReturn(firstReady);
        when(recovery.resume(second)).thenReturn(secondReady);
        when(recovery.resume(third)).thenReturn(thirdReady);
        when(publication.publish(firstReady)).thenReturn(PublishResult.SUPERSEDED);
        when(publication.publish(secondReady)).thenReturn(PublishResult.SUPERSEDED);
        when(publication.publish(thirdReady)).thenReturn(PublishResult.SUPERSEDED);

        CompactionOutcome outcome =
                new LsmCompactionCoordinator(planning, recovery, publication)
                        .compactIfRequired(request);

        assertThat(outcome.publishResult()).isEqualTo(PublishResult.SUPERSEDED);
        verify(planning, org.mockito.Mockito.times(3)).begin(request);
    }
}
