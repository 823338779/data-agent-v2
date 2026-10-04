package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.configuration.StepCompactionProperties;
import com.datagent.agentv2.context.hierarchical.domain.ContextExecutionFacts;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalStepSummaryDocument;
import com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalStepBudgetPlanner;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalStepSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalStepSummaryProtocol;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextPreparationException;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalStepContextEngineTest {
    private static final String ID = "id", HASH = "0".repeat(64), ASK = "完成指标开发，生成候选后等待确认";
    private static final String DONE = "已读取资料，继续原任务；候选仍需用户确认。";
    private final HierarchicalConversationHistoryLoader history =
            mock(HierarchicalConversationHistoryLoader.class);
    private final HierarchicalCheckpointRepository checkpoints =
            mock(HierarchicalCheckpointRepository.class);
    private final HierarchicalColdRepository colds = mock(HierarchicalColdRepository.class);
    private final ContextSummaryModelPort model = mock(ContextSummaryModelPort.class);
    private final ContextExecutionControlPort control = mock(ContextExecutionControlPort.class);
    private final AtomicReference<HierarchicalCheckpoint> checkpoint =
            new AtomicReference<>(new HierarchicalCheckpoint(ID, ID, null, 0, 0, 0));
    private final Map<String, HierarchicalCold> stored = new HashMap<>();
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private final List<ContextStepGroup> groups = new ArrayList<>();
    private final ConversationContextRequest request =
            new ConversationContextRequest(
                    new ContextExecutionRef(ID, ID, ID, 1),
                    new ContextBudget(16000, 1024, 512, 0),
                    List.of(),
                    List.of());

    @Test
    void sameMessageCanCompactTwiceAndKeepCurrentUserExactlyOnce() {
        var engine = engine();
        int published = 0;
        for (int step = 1; step <= 40; step++) {
            append(step);
            var prepared = engine.prepare(request);
            if (prepared.compacted()) {
                published++;
            }
            assertThat(
                            prepared.messages().stream()
                                    .filter(message -> ASK.equals(message.content()))
                                    .count())
                    .isEqualTo(1);
            assertThat(prepared.estimatedInputTokens())
                    .isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
        }
        assertThat(published).isGreaterThanOrEqualTo(2);
        assertThat(checkpoint.get().coveredThrough().messageSequence()).isEqualTo(2);
        assertThat(engine.prepare(request).messages())
                .anyMatch(message -> message.content().contains(DONE));
    }

    @Test
    void sameFailedFingerprintIsNotRetried() {
        var engine = engine();
        for (int i = 1; i <= 8; i++) {
            append(i);
        }
        doThrow(new IllegalStateException(ID)).when(model).summarize(any());
        engine.prepare(request);
        engine.prepare(request);
        verify(model, times(1)).summarize(any());
    }

    @Test
    void overHardBudgetFailsWithoutPublishing() {
        var engine = engine();
        for (int i = 1; i <= 15; i++) {
            append(i);
        }
        doThrow(new IllegalStateException(ID)).when(model).summarize(any());
        assertThatThrownBy(() -> engine.prepare(request))
                .isInstanceOf(ContextPreparationException.class);
        verify(colds, never()).insertOrSelect(any());
    }

    @Test
    void cancelDuringSummaryPreventsPublication() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        var guards = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (guards.incrementAndGet() >= 4) {
                                throw new ContextPreparationException("CANCELLED", "cancelled");
                            }
                            return null;
                        })
                .when(control)
                .assertRunningAndWithinDeadline(any());
        assertThatThrownBy(() -> engine.prepare(request))
                .isInstanceOf(ContextPreparationException.class);
        verify(model).summarize(any());
        verify(colds, never()).insertOrSelect(any());
        assertThat(checkpoint.get().strategyGeneration()).isZero();
    }

    @Test
    void restartLoadsPublishedSummaryAndSuffix() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        var first = engine.prepare(request);
        assertThat(first.compacted()).isTrue();
        engine.closeExecution(ID);
        var after = createEngine().prepare(request);
        assertThat(after.messages()).anyMatch(message -> message.content().contains(DONE));
        assertThat(after.strategyGeneration()).isEqualTo(first.strategyGeneration());
    }

    @Test
    void casLoserUsesWinnerWithoutOverwriting() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        doAnswer(
                        invocation -> {
                            HierarchicalCheckpoint value = invocation.getArgument(1);
                            var original = stored.get(value.activeColdId());
                            var doc =
                                    json.readValue(
                                            original.summaryDocument(),
                                            HierarchicalStepSummaryDocument.class);
                            var winnerDoc =
                                    new HierarchicalStepSummaryDocument(
                                            3,
                                            doc.coverage(),
                                            List.of(
                                                    new HierarchicalStepSummaryDocument.Entry(
                                                            HierarchicalStepSummaryDocument.Kind
                                                                    .FACT,
                                                            "winner",
                                                            List.of(
                                                                    doc.coverage()
                                                                            .throughInclusive()))),
                                            doc.executionFacts());
                            var winner =
                                    new HierarchicalCold(
                                            "winner",
                                            ID,
                                            original.coverage(),
                                            json.writeValueAsString(winnerDoc),
                                            original.persistedTokenCount(),
                                            20,
                                            original.targetRenderedTokens(),
                                            ID,
                                            ID,
                                            original.sourceContentSha256(),
                                            3,
                                            original.stepCoverage());
                            stored.put(winner.coldId(), winner);
                            checkpoint.set(
                                    new HierarchicalCheckpoint(
                                            ID,
                                            ID,
                                            winner.coldId(),
                                            value.evaluatedUpToConversationMessageSequence(),
                                            value.strategyGeneration(),
                                            value.version() + 1,
                                            value.coveredThrough(),
                                            value.evaluatedThrough(),
                                            3,
                                            3));
                            return false;
                        })
                .when(checkpoints)
                .compareAndSetCheckpoint(any(), any());
        assertThat(engine.prepare(request).messages())
                .anyMatch(message -> message.content().contains("winner"));
        verify(checkpoints).compareAndSetCheckpoint(any(), any());
    }

    @Test
    void criticalObservedIdentitySurvivesSummary() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        var source = groups.get(1);
        String output =
                "{\"validationId\":\"validation-42\",\"status\":\"SUCCEEDED\",\"body\":\""
                        + ID.repeat(1200)
                        + "\"}";
        groups.set(
                1,
                new ContextStepGroup(
                        source.fromExclusive(),
                        source.throughInclusive(),
                        List.of(
                                ContextMessage.assistantToolCalls(
                                        List.of(
                                                new ContextToolCall(
                                                        ID, "dbt_validation_get", Map.of()))),
                                ContextMessage.tool(output, ID, "dbt_validation_get")),
                        HASH,
                        true));
        var prepared = engine.prepare(request);
        assertThat(prepared.compacted()).isTrue();
        var doc =
                json.readValue(
                        stored.get(checkpoint.get().activeColdId()).summaryDocument(),
                        HierarchicalStepSummaryDocument.class);
        assertThat(doc.executionFacts().values())
                .containsEntry("validationId:validation-42.status", "SUCCEEDED");
        assertThat(prepared.messages())
                .anyMatch(message -> message.content().contains("validation-42"));
    }

    @Test
    void laterAppendDoesNotInvalidateCoveredPrefix() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        doAnswer(
                        invocation -> {
                            ContextSummaryRequest source = invocation.getArgument(0);
                            var root = json.readTree(source.sourceDocument());
                            var coverage =
                                    json.treeToValue(
                                            root.path("coverage"), StepSummaryCoverage.class);
                            append(groups.size());
                            var doc =
                                    new HierarchicalStepSummaryDocument(
                                            3,
                                            coverage,
                                            List.of(
                                                    new HierarchicalStepSummaryDocument.Entry(
                                                            HierarchicalStepSummaryDocument.Kind
                                                                    .FACT,
                                                            DONE,
                                                            List.of(coverage.throughInclusive()))));
                            return new ContextSummaryResult(
                                    json.writeValueAsString(doc), 0, ID, ID);
                        })
                .when(model)
                .summarize(any());
        assertThat(engine.prepare(request).compacted()).isTrue();
        assertThat(checkpoint.get().coveredThrough())
                .isLessThan(groups.getLast().throughInclusive());
    }

    @Test
    void incompletePrefixCannotBePublished() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        engine.prepare(request);
        var candidate = stored.get(checkpoint.get().activeColdId());
        var source = groups.get(1);
        groups.set(
                1,
                new ContextStepGroup(
                        source.fromExclusive(),
                        source.throughInclusive(),
                        source.messages(),
                        source.sourceDigest(),
                        false));
        var publisher = new HierarchicalStepPublisher(history, colds, checkpoints, control);
        assertThatThrownBy(
                        () ->
                                publisher.publish(
                                        request,
                                        checkpoint.get(),
                                        candidate,
                                        groups.getLast().throughInclusive()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentUserRemainsInSummarySourceButIsSentOnlyOnce() {
        var engine = engine();
        for (int index = 1; index <= 10; index++) {
            append(index);
        }
        var prepared = engine.prepare(request);
        var captured = org.mockito.ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model, org.mockito.Mockito.atLeastOnce()).summarize(captured.capture());
        assertThat(captured.getAllValues().getFirst().sourceDocument()).contains(ASK);
        assertThat(
                        prepared.messages().stream()
                                .filter(message -> ASK.equals(message.content()))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void completedOperationFactsDoNotGrowPinnedInputForever() {
        var engine = engine();
        for (int index = 1; index <= 44; index++) {
            String queryId = String.format("00000000-0000-0000-0000-%012d", index);
            var previous = groups.getLast().throughInclusive();
            groups.add(
                    new ContextStepGroup(
                            previous,
                            new ContextStepCursor(
                                    2, ContextStepCursor.PositionKind.INVOCATION_END, index),
                            List.of(
                                    ContextMessage.assistantToolCalls(
                                            List.of(
                                                    new ContextToolCall(
                                                            ID + index,
                                                            "dbt_query_get",
                                                            Map.of("queryId", queryId)))),
                                    ContextMessage.tool(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "queryId",
                                                            queryId,
                                                            "status",
                                                            index == 1 ? "RUNNING" : "SUCCEEDED")),
                                            ID + index,
                                            "dbt_query_get")),
                            HASH,
                            true));
        }
        var narrow =
                new ConversationContextRequest(
                        request.execution(),
                        new ContextBudget(8536, 1024, 512, 0),
                        List.of(),
                        List.of());
        var prepared = engine.prepare(narrow);
        assertThat(prepared.compacted()).isTrue();
        assertThat(prepared.estimatedInputTokens()).isLessThanOrEqualTo(7000);
        var summary =
                json.readValue(
                        stored.get(checkpoint.get().activeColdId()).summaryDocument(),
                        HierarchicalStepSummaryDocument.class);
        assertThat(summary.executionFacts().values())
                .containsEntry("queryId:00000000-0000-0000-0000-000000000001.status", "RUNNING")
                .containsEntry("queryId:00000000-0000-0000-0000-000000000044.status", "SUCCEEDED");
        assertThat(summary.executionFacts().values()).hasSize(4);
    }

    private HierarchicalStepContextEngine engine() {
        groups.add(
                new ContextStepGroup(
                        ContextStepCursor.ORIGIN,
                        new ContextStepCursor(1, ContextStepCursor.PositionKind.MESSAGE_END, 0),
                        List.of(ContextMessage.user(ASK)),
                        HASH,
                        true));
        when(history.loadSteps(ID)).thenAnswer(call -> List.copyOf(groups));
        when(checkpoints.findCheckpoint(ID)).thenAnswer(call -> checkpoint.get());
        when(colds.find(any(), any())).thenAnswer(call -> stored.get(call.getArgument(1)));
        when(colds.insertOrSelect(any()))
                .thenAnswer(
                        call -> {
                            HierarchicalCold cold = call.getArgument(0);
                            stored.put(cold.coldId(), cold);
                            return cold;
                        });
        when(checkpoints.compareAndSetCheckpoint(any(), any()))
                .thenAnswer(
                        call -> {
                            HierarchicalCheckpoint value = call.getArgument(1);
                            checkpoint.set(
                                    new HierarchicalCheckpoint(
                                            value.checkpointId(),
                                            ID,
                                            value.activeColdId(),
                                            value.evaluatedUpToConversationMessageSequence(),
                                            value.strategyGeneration(),
                                            value.version() + 1,
                                            value.coveredThrough(),
                                            value.evaluatedThrough(),
                                            3,
                                            3));
                            return true;
                        });
        when(model.identity()).thenReturn(new ContextSummaryModelIdentity(ID, ID));
        when(model.summarize(any()))
                .thenAnswer(
                        call -> {
                            ContextSummaryRequest source = call.getArgument(0);
                            var root = json.readTree(source.sourceDocument());
                            var coverage =
                                    json.treeToValue(
                                            root.path("coverage"), StepSummaryCoverage.class);
                            var facts =
                                    json.treeToValue(
                                            root.path("executionFacts"),
                                            ContextExecutionFacts.class);
                            var doc =
                                    new HierarchicalStepSummaryDocument(
                                            3,
                                            coverage,
                                            List.of(
                                                    new HierarchicalStepSummaryDocument.Entry(
                                                            HierarchicalStepSummaryDocument.Kind
                                                                    .FACT,
                                                            DONE,
                                                            List.of(coverage.throughInclusive()))),
                                            facts);
                            return new ContextSummaryResult(
                                    json.writeValueAsString(doc), 0, ID, ID);
                        });
        return createEngine();
    }

    private HierarchicalStepContextEngine createEngine() {
        var properties = new StepCompactionProperties();
        var estimator = new ConversationTokenEstimator();
        var protocol = new HierarchicalStepSummaryProtocol();
        return new HierarchicalStepContextEngine(
                history,
                checkpoints,
                colds,
                properties,
                estimator,
                protocol,
                new HierarchicalStepBudgetPlanner(estimator, properties),
                new HierarchicalStepSummaryGenerator(model, protocol, estimator),
                new HierarchicalStepPublisher(history, colds, checkpoints, control),
                control,
                model,
                HierarchicalContextMetrics.isolated());
    }

    private void append(int step) {
        var previous = groups.getLast().throughInclusive();
        groups.add(
                new ContextStepGroup(
                        previous,
                        new ContextStepCursor(
                                2, ContextStepCursor.PositionKind.INVOCATION_END, step),
                        List.of(ContextMessage.assistant(ID.repeat(1200), null)),
                        HASH,
                        true));
    }
}
