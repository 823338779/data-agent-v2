package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.common.summary.EventContextSummaryRenderer;
import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTurnGrouper;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalColdKeyFactory;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.conversation.context.RuntimeContextContribution;
import com.datagent.agentv2.conversation.context.RuntimeContextInput;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalConversationContextAssemblerTest {
    private final HierarchicalConversationHistoryLoader history =
            mock(HierarchicalConversationHistoryLoader.class);
    private final HierarchicalCheckpointRepository checkpoints =
            mock(HierarchicalCheckpointRepository.class);
    private final HierarchicalColdRepository colds = mock(HierarchicalColdRepository.class);
    private final HierarchicalConversationContextAssembler assembler =
            new HierarchicalConversationContextAssembler(
                    history,
                    new ConversationTokenEstimator(),
                    checkpoints,
                    colds,
                    new EventContextSummaryValidator(),
                    new EventContextSummaryRenderer());
    private final ConversationContextRequest request = request();
    private final HierarchicalContextPolicySnapshot policy =
            new HierarchicalContextPolicySnapshot(1, .75, .70);

    @BeforeEach
    void defineCheckpoint() {
        when(checkpoints.findCheckpoint("session")).thenReturn(checkpoint(null));
    }

    @Test
    void lowerTargetRatioReducesTheSummaryBudget() {
        when(history.load(request.execution())).thenReturn(turns("old", "latest"));
        var lower = new HierarchicalContextPolicySnapshot(1, .75, .50);
        int defaultBudget =
                assembler
                        .compactionInput(assembler.loadAndEstimate(request, policy), 32768)
                        .historyMemoryBudget();
        int lowerBudget =
                assembler
                        .compactionInput(assembler.loadAndEstimate(request, lower), 32768)
                        .historyMemoryBudget();
        assertThat(defaultBudget - lowerBudget).isEqualTo(13107);
    }

    @Test
    void realHistoryLoaderLeavesDatabaseMessagesUnchanged() {
        var executions = mock(AgentExecutionService.class);
        var messages = mock(ConversationMessageService.class);
        var execution =
                AgentExecution.running(
                        "execution", "session", "user", "key", "hash", java.time.Instant.MAX);
        when(executions.findByExecutionId("execution")).thenReturn(execution);
        var user = ConversationMessage.user("user", "session", "原始消息", 1, "user");
        var answer = ConversationMessage.assistant("answer", "session", "execution", "原始回答", 2);
        when(messages.findByConversationSessionId("session")).thenReturn(List.of(user, answer));
        var loader =
                new HierarchicalConversationHistoryLoader(
                        executions,
                        messages,
                        JsonMapper.builder().build(),
                        new ConversationTurnGrouper());
        var realAssembler =
                new HierarchicalConversationContextAssembler(
                        loader,
                        new ConversationTokenEstimator(),
                        checkpoints,
                        colds,
                        new EventContextSummaryValidator(),
                        new EventContextSummaryRenderer());
        var state = realAssembler.loadAndEstimate(withMemory(request), policy);
        assertThat(realAssembler.prepareCurrentView(state).runtimeStatistics().injected())
                .isEqualTo(1);
        assertThat(user.getContent()).isEqualTo("原始消息");
        assertThat(answer.getContent()).isEqualTo("原始回答");
        assertThat(state.turns())
                .flatExtracting(ConversationTurn::messages)
                .extracting(ContextMessage::content)
                .containsExactly("原始消息", "原始回答");
        Mockito.verify(messages).findByConversationSessionId("session");
        Mockito.verifyNoMoreInteractions(messages);
    }

    @Test
    void hardBoundaryKeepsNecessaryMessagesAndOmitsOptionalMemory() {
        when(history.load(request.execution())).thenReturn(turns("latest"));
        var raw = turns("latest").getFirst().messages();
        int necessary =
                new ConversationTokenEstimator().estimate(request, raw)
                        + new ConversationTokenEstimator()
                                .estimateMessages(
                                        List.of(ContextMessage.user("evidence_id=real-user")));
        var tight =
                new ConversationContextRequest(
                        request.execution(),
                        new ContextBudget(necessary + 1, 1, 0, 0),
                        List.of(),
                        List.of());
        var prepared =
                assembler.prepareCurrentView(assembler.loadAndEstimate(withMemory(tight), policy));
        assertThat(prepared.estimatedInputTokens())
                .isEqualTo(tight.budget().hardInputLimitTokens());
        assertThat(prepared.runtimeStatistics().injected()).isZero();
        assertThat(prepared.messages()).containsAll(raw);
    }

    @Test
    void usesActualSelectionForHistoryBudgetAndKeepsSummaryModelSourceClean() {
        String[] contents =
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(i -> "old".repeat(20))
                        .toArray(String[]::new);
        var original = turns(contents);
        when(history.load(request.execution())).thenReturn(original);
        var base =
                new ConversationContextRequest(
                        request.execution(),
                        new ContextBudget(2800, 1, 0, 0),
                        List.of(),
                        List.of());
        var plain = assembler.loadAndEstimate(base, policy);
        var enriched = assembler.loadAndEstimate(withMemory(base), policy);
        var prepared = assembler.prepareCurrentView(enriched);
        assertThat(prepared.runtimeStatistics().injected()).isEqualTo(1);
        int runtimeCost = enriched.estimatedTokens() - plain.estimatedTokens();
        var input = assembler.compactionInput(enriched, 32768);
        assertThat(input.historyMemoryBudget())
                .isEqualTo(
                        assembler.compactionInput(plain, 32768).historyMemoryBudget()
                                - runtimeCost);
        var model = mock(ContextSummaryModelPort.class);
        when(model.summarize(ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            ContextSummaryRequest source = call.getArgument(0);
                            assertThat(source.sourceDocument())
                                    .contains("old")
                                    .doesNotContain("用户主要使用中文", "evidence_id");
                            return new ContextSummaryResult(
                                    cold().summaryDocument(), 100, "provider", "model");
                        });
        var generator =
                new RollingColdSummaryGenerator(
                        model,
                        new EventContextSummaryValidator(),
                        new EventContextSummaryRenderer(),
                        new ConversationTokenEstimator(),
                        new HierarchicalColdKeyFactory());
        var coverage = new EventSummaryCoverage(1, 1, 1, 2);
        generator.generate(
                new RollingColdSummaryGenerator.GenerationRequest(
                        "tenant",
                        "session",
                        null,
                        List.of(
                                new RollingColdSummaryGenerator.GenerationBatch(
                                        coverage, input.state().turns().subList(0, 1))),
                        coverage,
                        1000,
                        1024,
                        32768,
                        new ContextSummaryModelIdentity("provider", "model")));
        Mockito.verify(model).summarize(ArgumentMatchers.any());
        assertThat(enriched.turns()).isEqualTo(original);
    }

    @Test
    void historyBudgetDoesNotReselectOptionalMemoryAfterShrinkingToProtectedTurns() {
        String[] contents =
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(i -> "old".repeat(20))
                        .toArray(String[]::new);
        when(history.load(request.execution())).thenReturn(turns(contents));
        var base =
                new ConversationContextRequest(
                        request.execution(),
                        new ContextBudget(2000, 1, 0, 0),
                        List.of(),
                        List.of());
        var enriched = withMemory(base);
        var metadataOnly =
                new ConversationContextRequest(
                        base.execution(),
                        base.budget(),
                        List.of(),
                        List.of(),
                        new RuntimeContextInput(enriched.runtimeInput().metadata(), List.of()));
        var state = assembler.loadAndEstimate(enriched, policy);
        assertThat(assembler.prepareCurrentView(state).runtimeStatistics().injected()).isZero();
        assertThat(assembler.compactionInput(state, 32768).historyMemoryBudget())
                .isEqualTo(
                        assembler
                                .compactionInput(
                                        assembler.loadAndEstimate(metadataOnly, policy), 32768)
                                .historyMemoryBudget());
    }

    @Test
    void personalMemoryIsTransientUserContextWithPairedToolsAndFinalStatistics() {
        var enriched = withMemory(request);
        var call =
                new ContextMessage(
                        ContextMessage.Role.ASSISTANT,
                        "",
                        null,
                        List.of(new ContextToolCall("call", "tool", Map.of())),
                        null,
                        null);
        var result = ContextMessage.tool("result", "call", "tool");
        var original =
                List.of(
                        new ConversationTurn(
                                1, 1, 2, List.of(ContextMessage.user("latest"), call, result)));
        when(history.load(request.execution())).thenReturn(original);
        var state = assembler.loadAndEstimate(enriched, policy);
        var prepared = assembler.prepareCurrentView(state);
        assertThat(prepared.messages())
                .extracting(ContextMessage::content)
                .containsExactly("evidence_id=real-user", "历史数据\n用户主要使用中文", "latest", "", "result");
        assertThat(prepared.messages().get(1).role()).isEqualTo(ContextMessage.Role.USER);
        assertThat(prepared.messages()).containsSubsequence(call, result);
        assertThat(state.turns()).isEqualTo(original);
        assertThat(prepared.estimatedInputTokens())
                .isEqualTo(state.estimatedTokens())
                .isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
        assertThat(prepared.runtimeStatistics().injected()).isEqualTo(1);
        assertThat(prepared.runtimeStatistics().omitted()).isZero();
        assertThat(prepared.runtimeStatistics().contributionTokens())
                .isEqualTo(
                        new ConversationTokenEstimator()
                                .estimateMessages(List.of(prepared.messages().get(1))));
    }

    @Test
    void optionalMemoryCannotTriggerCompression() {
        var original = turns("x".repeat(200), "latest");
        when(history.load(request.execution())).thenReturn(original);
        var raw = original.stream().flatMap(t -> t.messages().stream()).toList();
        var metadata = ContextMessage.user("evidence_id=real-user");
        int necessary =
                new ConversationTokenEstimator().estimate(request, raw)
                        + new ConversationTokenEstimator().estimateMessages(List.of(metadata));
        var tight =
                new ConversationContextRequest(
                        request.execution(),
                        new ContextBudget((int) Math.ceil(necessary / .75), 1, 0, 0),
                        List.of(),
                        List.of());
        var state = assembler.loadAndEstimate(withMemory(tight), policy);
        var prepared = assembler.prepareCurrentView(state);
        assertThat(state.estimatedTokens())
                .isLessThanOrEqualTo(policy.softTriggerTokens(tight.budget()));
        assertThat(prepared.runtimeStatistics().injected()).isZero();
        assertThat(prepared.runtimeStatistics().omitted()).isEqualTo(1);
        assertThat(prepared.messages()).containsAll(raw).contains(metadata);
    }

    private static ConversationContextRequest withMemory(ConversationContextRequest request) {
        return new ConversationContextRequest(
                request.execution(),
                request.budget(),
                request.fixedSystemMessages(),
                request.toolDefinitions(),
                new RuntimeContextInput(
                        List.of(ContextMessage.user("evidence_id=real-user")),
                        List.of(
                                new RuntimeContextContribution(
                                        "personal-memory", "历史数据", List.of("用户主要使用中文"), 1024))));
    }

    @Test
    void keepsEveryUncoveredTurnInOrderWithoutSelectingACurrentTurn() {
        when(history.load(request.execution())).thenReturn(turns("old", "failed", "latest"));

        HierarchicalContextState state = assembler.loadAndEstimate(request, policy);
        PreparedConversationContext prepared = assembler.prepareCurrentView(state);

        assertThat(state.loadedThroughMessageSequence()).isEqualTo(6L);
        assertThat(prepared.messages())
                .filteredOn(message -> message.role() == ContextMessage.Role.USER)
                .extracting(ContextMessage::content)
                .containsExactly("old", "failed", "latest");
    }

    @Test
    void rendersColdBeforeEveryUncoveredTurn() {
        when(checkpoints.findCheckpoint("session")).thenReturn(checkpoint("cold-1"));
        when(colds.find("session", "cold-1")).thenReturn(cold());
        when(history.load(request.execution())).thenReturn(turns("old", "failed", "latest"));

        PreparedConversationContext prepared =
                assembler.prepareCurrentView(assembler.loadAndEstimate(request, policy));

        assertThat(prepared.messages().getFirst().role()).isEqualTo(ContextMessage.Role.SYSTEM);
        assertThat(prepared.messages().getFirst().content()).contains("旧事实");
        assertThat(prepared.messages())
                .filteredOn(message -> message.role() == ContextMessage.Role.USER)
                .extracting(ContextMessage::content)
                .containsExactly("failed", "latest");
    }

    private static List<ConversationTurn> turns(String... userContents) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int index = 0; index < userContents.length; index++) {
            long ordinal = index + 1L;
            long start = ordinal * 2L - 1L;
            turns.add(
                    new ConversationTurn(
                            ordinal,
                            start,
                            start + 1L,
                            List.of(
                                    ContextMessage.user(userContents[index]),
                                    ContextMessage.assistant("answer-" + ordinal, null))));
        }
        return List.copyOf(turns);
    }

    private static ConversationContextRequest request() {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", 1L),
                new ContextBudget(65_536, 4_096, 4_096, 0),
                List.of(),
                List.of());
    }

    private static HierarchicalCheckpoint checkpoint(String activeColdId) {
        return new HierarchicalCheckpoint("checkpoint", "session", activeColdId, 0, 0, 0);
    }

    private static HierarchicalCold cold() {
        return new HierarchicalCold(
                "cold-1",
                "session",
                new EventSummaryCoverage(1, 1, 1, 2),
                """
                {
                  "schemaVersion": 2,
                  "coverage": {"startTurnSequence":1,"endTurnSequence":1,"startMessageSequence":1,"endMessageSequence":2},
                  "events": [{
                    "title": {"text":"旧事实","sourceRanges":[{"startMessageSequence":1,"endMessageSequence":1}]},
                    "status": {"value":"ACTIVE","sourceRanges":[{"startMessageSequence":2,"endMessageSequence":2}]},
                    "confirmedFacts": [{"statement":"旧事实","sourceRanges":[{"startMessageSequence":1,"endMessageSequence":2}]}],
                    "userPreferences": [], "workingHypotheses": [], "decisions": [],
                    "rejectedApproaches": [], "toolFindings": [], "openQuestions": [], "nextSteps": []
                  }]
                }
                """,
                100,
                80,
                100,
                "provider",
                "model",
                "0".repeat(64));
    }
}
