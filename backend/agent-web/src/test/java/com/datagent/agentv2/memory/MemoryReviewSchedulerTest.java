package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.review.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MemoryReviewSchedulerTest {
    @Test
    void doesNotClaimBeforeReadyOrWhenAllFourWorkersAreOccupied() throws Exception {
        var coordinator = mock(MemoryReviewCoordinator.class);
        var worker = mock(MemoryReviewWorker.class);
        var properties = new MemoryProperties();
        var pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
        var timer = Executors.newSingleThreadScheduledExecutor();
        var scheduler = new MemoryReviewScheduler(coordinator, worker, properties, pool, timer);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(4);
        try {
            scheduler.scan();
            verifyNoInteractions(coordinator);
            for (int i = 0; i < 4; i++)
                pool.execute(
                        () -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                            }
                        });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.ready();
            scheduler.scan();
            verify(coordinator, never()).claimDue(anyString(), anyInt());
            assertThat(pool.getPoolSize()).isEqualTo(4);
            assertThat(pool.getQueue()).isEmpty();
        } finally {
            scheduler.destroy();
            release.countDown();
            pool.shutdownNow();
            timer.shutdownNow();
        }
    }
}
