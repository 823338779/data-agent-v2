package com.datagent.agentv2.memory.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.memory.*;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.tool.MemoryToolDispatcher;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MemoryReviewWorkerTest {
    final MemoryProperties properties = new MemoryProperties();
    final MemoryReviewCoordinator coordinator = mock(MemoryReviewCoordinator.class);
    final MemoryService memory = mock(MemoryService.class);
    final MemoryReviewHistoryPort history = mock(MemoryReviewHistoryPort.class);
    final MemoryReviewModelPort model = mock(MemoryReviewModelPort.class);
    final MemoryOperationService operations = mock(MemoryOperationService.class);
    final ObjectMapper json = new ObjectMapper();
    final ScheduledExecutorService guards = Executors.newScheduledThreadPool(2);
    final MemoryReviewClaim claim =
            new MemoryReviewClaim("j", "s", 1, 1, 1, "o", 1, 0, 2, Instant.now().plusSeconds(120));
    MemoryReviewWorker worker;

    @BeforeEach
    void setup() {
        when(memory.snapshot()).thenReturn(snapshot(10));
        when(history.read(eq(claim), anyLong(), anyInt()))
                .thenReturn(
                        List.of(
                                MemoryReviewSliceSelectorTest.row(1, "A和B"),
                                MemoryReviewSliceSelectorTest.row(2, "后续")));
        when(model.maxInputTokens()).thenReturn(1000);
        when(model.estimate(any())).thenReturn(50);
        when(model.configurationFingerprint()).thenReturn("config");
        when(coordinator.renew(claim)).thenReturn(true);
        when(coordinator.finish(eq(claim), any())).thenReturn(MemoryReviewJob.Status.COMPLETED);
        worker =
                new MemoryReviewWorker(
                        properties,
                        coordinator,
                        memory,
                        history,
                        model,
                        new MemoryToolDispatcher(memory, json),
                        operations,
                        new MemoryContentPolicy(properties),
                        json,
                        guards);
    }

    @AfterEach
    void close() {
        guards.shutdownNow();
        Thread.interrupted();
    }

    @Test
    void existingExplicitAStillReviewsBAndCarriesTrustedPromptVersion() {
        when(memory.snapshot()).thenReturn(snapshot(10), snapshot(11));
        when(model.complete(any())).thenReturn(calls(add()), new MemoryReviewOutput.Done());
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.CREATED, 11));
        worker.run(claim);
        var context = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(memory).execute(context.capture(), any());
        assertThat(context.getValue().promptVersion()).isEqualTo(MemoryReviewPrompts.VERSION);
        assertThat(context.getValue().expectedRevision()).isEqualTo(10);
        verify(coordinator).finish(claim, new MemoryReviewProgress(2, 0));
        var inputs = ArgumentCaptor.forClass(MemoryReviewInput.class);
        verify(model, times(2)).complete(inputs.capture());
        assertThat(inputs.getAllValues().getLast().snapshot().state().revision()).isEqualTo(11);
    }

    @ParameterizedTest
    @EnumSource(
            value = MemoryStatus.class,
            names = {"UNSAFE_CONTENT", "PROTECTED", "SUPPRESSED", "QUOTA_EXCEEDED"})
    void definitiveBusinessRefusalsAllowCompletion(MemoryStatus status) {
        when(model.complete(any())).thenReturn(calls(add()), new MemoryReviewOutput.Done());
        when(memory.execute(any(), any())).thenReturn(outcome(status, 10));
        worker.run(claim);
        verify(coordinator).finish(claim, new MemoryReviewProgress(2, 1));
    }

    @ParameterizedTest
    @EnumSource(
            value = MemoryStatus.class,
            names = {
                "INVALID_REQUEST",
                "CONFLICT",
                "UNAVAILABLE",
                "LEASE_LOST",
                "DUPLICATE_CONFLICT",
                "NOT_FOUND"
            })
    void unresolvedResultsNeverAdvance(MemoryStatus status) {
        when(model.complete(any())).thenReturn(calls(add()));
        when(memory.execute(any(), any())).thenReturn(outcome(status, 10));
        worker.run(claim);
        verify(coordinator, never()).finish(any(), any());
        verify(coordinator).fail(eq(claim), eq(status.name()), eq(false), anyString());
    }

    @Test
    void missingEstimatorBlocksWithoutModelCall() {
        when(model.maxInputTokens()).thenThrow(new MemoryReviewConfigurationException());
        worker.run(claim);
        verify(model, never()).complete(any());
        verify(coordinator).fail(eq(claim), eq("MODEL_CONFIGURATION"), eq(true), anyString());
    }

    @Test
    void duplicateActionsInOneOutputAreSubmittedOnlyOnce() {
        when(model.complete(any()))
                .thenReturn(
                        new MemoryReviewOutput.Calls(
                                List.of(
                                        new MemoryReviewOutput.Call("first", add()),
                                        new MemoryReviewOutput.Call("second", add()))),
                        new MemoryReviewOutput.Done());
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.PROTECTED, 10));
        worker.run(claim);
        verify(memory, times(1)).execute(any(), any());
        verify(coordinator).finish(eq(claim), any());
    }

    @Test
    void invalidExtraArgumentsCannotMasqueradeAsHistoricalSuccess() {
        var args = new HashMap<>(add());
        args.put("source", "user_ui");
        when(model.complete(any())).thenReturn(calls(args));
        worker.run(claim);
        verify(operations, never()).findSuccessfulReviewAction(anyString());
        verify(coordinator).fail(eq(claim), eq("INVALID_REQUEST"), eq(false), anyString());
    }

    @Test
    void oversizedFirstMessageBlocksWithoutAdvancing() {
        when(model.estimate(any())).thenReturn(2000);
        worker.run(claim);
        verify(model, never()).complete(any());
        verify(coordinator).fail(eq(claim), eq("INPUT_TOO_LARGE"), eq(true), anyString());
        verify(coordinator, never()).finish(any(), any());
    }

    @Test
    void renewalLossStopsLateModelResponse() throws Exception {
        properties.setLeaseDuration(Duration.ofMillis(90));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(coordinator.renew(claim)).thenReturn(false);
        when(model.complete(any()))
                .thenAnswer(
                        i -> {
                            entered.countDown();
                            while (release.getCount() > 0) {
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return calls(add());
                        });
        var thread = new Thread(() -> worker.run(claim));
        thread.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        verify(coordinator, timeout(2000))
                .fail(eq(claim), eq("LEASE_LOST"), eq(false), anyString());
        release.countDown();
        thread.join(2000);
        assertThat(thread.isAlive()).isFalse();
        verify(memory, never()).execute(any(), any());
    }

    @Test
    void modelLimitDoesNotAdvanceUnresolvedBatch() {
        properties.setMaxModelCalls(1);
        when(model.complete(any())).thenReturn(calls(add()));
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.PROTECTED, 10));
        worker.run(claim);
        verify(coordinator).fail(eq(claim), eq("MODEL_CALL_LIMIT"), eq(false), anyString());
        verify(coordinator, never()).finish(any(), any());
    }

    @Test
    void maintenanceLimitStopsBeforeSecondSubmission() {
        properties.setMaxMaintenanceCalls(1);
        var second = new HashMap<>(add());
        second.put("content", "另一个事实");
        when(model.complete(any()))
                .thenReturn(
                        new MemoryReviewOutput.Calls(
                                List.of(
                                        new MemoryReviewOutput.Call("first", add()),
                                        new MemoryReviewOutput.Call("second", second))));
        when(memory.execute(any(), any())).thenReturn(outcome(MemoryStatus.PROTECTED, 10));
        worker.run(claim);
        verify(memory, times(1)).execute(any(), any());
        verify(coordinator).fail(eq(claim), eq("MAINTENANCE_LIMIT"), eq(false), anyString());
        verify(coordinator, never()).finish(any(), any());
    }

    @Test
    void assistantContextCannotBeUsedAsEvidence() {
        var original = MemoryReviewSliceSelectorTest.row(1, "助手语境").evidence();
        var assistant =
                new MemoryEvidence(
                        original.evidenceId(),
                        MemoryEvidence.Kind.ASSISTANT_CONTEXT,
                        original.conversationId(),
                        original.executionId(),
                        original.sequence(),
                        null,
                        1,
                        original.content(),
                        original.createdAt());
        when(history.read(eq(claim), anyLong(), anyInt()))
                .thenReturn(
                        List.of(
                                new MemoryReviewMessage(
                                        1, MemoryReviewMessage.State.ELIGIBLE, assistant)));
        when(model.complete(any())).thenReturn(calls(add()));
        worker.run(claim);
        verify(memory, never()).execute(any(), any());
        verify(coordinator).fail(eq(claim), eq("INVALID_REQUEST"), eq(false), anyString());
    }

    @Test
    void slowDeadlineFailureIsNotOverwrittenByFinallyRelease() throws Exception {
        properties.setReviewDeadline(Duration.ofMillis(50));
        var failEntered = new CountDownLatch(1);
        var finishFailure = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            failEntered.countDown();
                            finishFailure.await(2, TimeUnit.SECONDS);
                            return null;
                        })
                .when(coordinator)
                .fail(eq(claim), eq("DEADLINE"), eq(false), anyString());
        when(model.complete(any()))
                .thenAnswer(
                        i -> {
                            try {
                                new CountDownLatch(1).await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                            }
                            return new MemoryReviewOutput.Done();
                        });
        var thread = new Thread(() -> worker.run(claim));
        thread.start();
        try {
            assertThat(failEntered.await(2, TimeUnit.SECONDS)).isTrue();
            thread.join(1000);
            assertThat(thread.isAlive()).isFalse();
            verify(coordinator, never()).release(claim);
            verify(coordinator, never()).finish(any(), any());
        } finally {
            finishFailure.countDown();
        }
    }

    @Test
    void successfulReplaceFromOlderSnapshotIsNotSubmittedAgain() {
        var args =
                Map.<String, Object>of(
                        "action",
                        "replace",
                        "memory_id",
                        "m",
                        "expected_version",
                        3,
                        "new_content",
                        "新偏好",
                        "evidence_message_ids",
                        List.of("u1"));
        when(model.complete(any())).thenReturn(calls(args), new MemoryReviewOutput.Done());
        when(operations.findSuccessfulReviewAction(anyString()))
                .thenReturn(
                        new MemoryOperation(
                                "old",
                                "hash",
                                outcome(MemoryStatus.UPDATED, 7),
                                LocalDateTime.now()));
        worker.run(claim);
        verify(memory, never()).execute(any(), any());
        verify(coordinator).finish(claim, new MemoryReviewProgress(2, 0));
        var inputs = ArgumentCaptor.forClass(MemoryReviewInput.class);
        verify(model, times(2)).complete(inputs.capture());
        assertThat(inputs.getAllValues().getLast().snapshot().state().revision()).isEqualTo(10);
    }

    @Test
    void deadlineStopsSubmissionEvenIfProviderIgnoresInterrupt() throws Exception {
        properties.setReviewDeadline(Duration.ofMillis(40));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(model.complete(any()))
                .thenAnswer(
                        i -> {
                            entered.countDown();
                            while (release.getCount() > 0) {
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return calls(add());
                        });
        var thread = new Thread(() -> worker.run(claim));
        thread.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        verify(coordinator, timeout(2000)).fail(eq(claim), eq("DEADLINE"), eq(false), anyString());
        release.countDown();
        thread.join(2000);
        assertThat(thread.isAlive()).isFalse();
        verify(memory, never()).execute(any(), any());
    }

    static MemorySnapshot snapshot(long revision) {
        return new MemorySnapshot(
                new MemoryState(
                        revision, 0, true, true, true, 1, LocalDateTime.of(2026, 9, 17, 0, 0)),
                List.of(),
                Map.of());
    }

    static MemoryOutcome outcome(MemoryStatus status, long revision) {
        return new MemoryOutcome(
                status, "m", 2L, revision, status == MemoryStatus.CREATED, true, Map.of());
    }

    static Map<String, Object> add() {
        return Map.of(
                "action",
                "add",
                "kind",
                "person_profile",
                "content",
                "B偏好",
                "evidence_message_ids",
                List.of("u1"));
    }

    static MemoryReviewOutput calls(Map<String, Object> args) {
        return new MemoryReviewOutput.Calls(List.of(new MemoryReviewOutput.Call("provider", args)));
    }
}
