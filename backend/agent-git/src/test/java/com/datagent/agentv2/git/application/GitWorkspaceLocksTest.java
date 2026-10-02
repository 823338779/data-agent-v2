package com.datagent.agentv2.git.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.git.model.GitException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitWorkspaceLocksTest {
    @Test
    void interruptedWaiterNeverExecutesItsMutation() throws Exception {
        GitWorkspaceLocks locks = new GitWorkspaceLocks();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean mutated = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        locks.workspace(
                                                "repo",
                                                "session",
                                                () -> {
                                                    held.countDown();
                                                    try {
                                                        release.await();
                                                    } catch (InterruptedException exception) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return null;
                                                }));
        assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
        Thread waiter =
                Thread.ofVirtual()
                        .unstarted(
                                () -> {
                                    try {
                                        locks.workspace(
                                                "repo",
                                                "session",
                                                () -> {
                                                    mutated.set(true);
                                                    return null;
                                                });
                                    } catch (Throwable exception) {
                                        failure.set(exception);
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                    }
                                });
        try {
            waiter.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(waiter.getState()).isEqualTo(Thread.State.WAITING);
            waiter.interrupt();
            release.countDown();
            waiter.join(2000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(mutated).isFalse();
            assertThat(failure.get()).isInstanceOf(GitException.class);
            assertThat(((GitException) failure.get()).code()).isEqualTo("GIT_INTERRUPTED");
            assertThat(interrupted).isTrue();
        } finally {
            release.countDown();
            holder.join(2000);
            waiter.interrupt();
            waiter.join(2000);
        }
    }
}
