package com.datagent.agentv2.conversation.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalSseRegistryConcurrencyTest {
    @Test
    void snapshotCanResumeWhileSubscriberIsWaitingForItsOwnLock() throws Exception {
        var registry = new LocalSseRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registration =
                registry.register(
                        "tenant",
                        "session",
                        new LocalSseRegistry.Subscriber() {
                            public void onDelta(LocalExecutionDelta delta) {
                                entered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }

                            public void onGap(LocalExecutionDelta delta) {
                                onDelta(delta);
                            }
                        });
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            executor.submit(
                    () ->
                            registry.publish(
                                    new LocalExecutionDelta(
                                            "tenant",
                                            "session",
                                            "run",
                                            1,
                                            1,
                                            "answer",
                                            Instant.now())));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> registration.resume("run", 1, 1)).get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            registration.close();
            executor.shutdownNow();
        }
    }
}
