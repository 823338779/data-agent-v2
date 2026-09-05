package com.datagent.agentv2.artifact.recovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.artifact.config.ArtifactProperties;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import com.datagent.agentv2.persistence.service.artifact.ArtifactBuildJobService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ArtifactRecoveryRunnerTest {

    @Test
    void failsInterruptedJobsBeforeCleaningExpiredStaging() throws Exception {
        ArtifactBuildJobService jobs = mock(ArtifactBuildJobService.class);
        ArtifactStorage storage = mock(ArtifactStorage.class);
        ArtifactProperties properties = new ArtifactProperties();
        properties.setStagingRetention(Duration.ofHours(12));
        when(jobs.failInterruptedBuilds(eq("PROCESS_RESTARTED"), any())).thenReturn(true);
        ArtifactRecoveryRunner runner = new ArtifactRecoveryRunner(jobs, storage, properties);

        runner.run(null);

        InOrder order = inOrder(jobs, storage);
        order.verify(jobs).failInterruptedBuilds(eq("PROCESS_RESTARTED"), any(Instant.class));
        order.verify(storage).deleteStagingOlderThan(any(Instant.class));
    }

    @Test
    void cleanupFailureDoesNotUndoDatabaseRecovery() throws Exception {
        ArtifactBuildJobService jobs = mock(ArtifactBuildJobService.class);
        ArtifactStorage storage = mock(ArtifactStorage.class);
        when(storage.deleteStagingOlderThan(any())).thenThrow(new IOException("disk unavailable"));
        ArtifactRecoveryRunner runner =
                new ArtifactRecoveryRunner(jobs, storage, new ArtifactProperties());

        runner.run(null);

        verify(jobs).failInterruptedBuilds(eq("PROCESS_RESTARTED"), any(Instant.class));
    }
}
