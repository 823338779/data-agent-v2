package com.datagent.agentv2.conversation.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalAgentExecutionRegistryTest {

    @Test
    void rejectsDuplicateExecutionAndInterruptsRegisteredTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        try (LocalAgentExecutionRegistry registry = new LocalAgentExecutionRegistry()) {
            assertThat(registry.start("execution_1", () -> awaitInterrupt(started, interrupted)))
                    .isTrue();
            assertThat(registry.start("execution_1", () -> {})).isFalse();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.cancel("execution_1")).isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitInterrupt(CountDownLatch started, CountDownLatch interrupted) {
        started.countDown();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException expected) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
    }
}
