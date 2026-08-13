package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalConversationContextAssemblerTest {

    private static final String SESSION_ID = "session";
    private static final String EMPTY_SUMMARY = "{\"schemaVersion\":1,\"confirmedFacts\":[]}";
    private static final String COLD_SUMMARY =
            "{\"schemaVersion\":1,\"confirmedFacts\":[\"cold\"]}";
    private static final String WARM_ONE_SUMMARY =
            "{\"schemaVersion\":1,\"confirmedFacts\":[\"warm-1\"]}";
    private static final String WARM_TWO_SUMMARY =
            "{\"schemaVersion\":1,\"confirmedFacts\":[\"warm-2\"]}";
    private static final String EMERGENCY_SUMMARY =
            "{\"schemaVersion\":1,\"confirmedFacts\":[\"emergency\"]}";
    private static final String EMPTY_FRONTIER = "{\"schemaVersion\":1,\"nodes\":[]}";
    private static final String MODEL_PROVIDER = "provider";
    private static final String MODEL_NAME = "model";
    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);

    private HierarchicalConversationHistoryLoader history;
    private HierarchicalCheckpointRepository checkpoints;
    private HierarchicalSummaryNodeRepository nodes;
    private HierarchicalConversationContextAssembler assembler;

    @BeforeEach
    void setUp() {
        history = mock(HierarchicalConversationHistoryLoader.class);
        checkpoints = mock(HierarchicalCheckpointRepository.class);
        nodes = mock(HierarchicalSummaryNodeRepository.class);
        assembler =
                new HierarchicalConversationContextAssembler(
                        history,
                        new ConversationTokenEstimator(),
                        checkpoints,
                        nodes,
                        JsonMapper.builder().build());
    }

    @Test
    void currentViewKeepsFixedFrameworkAndAllRawHistoryBelowTrigger() {
        ConversationContextRequest request = request(new ContextBudget(4_096, 256, 128, 11));
        List<ConversationTurn> turns = turns(2, true);
        HierarchicalCheckpoint checkpoint = checkpoint(0L, 0L, EMPTY_SUMMARY, EMPTY_FRONTIER, 0L);
        when(history.load(request.execution())).thenReturn(turns);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(checkpoint);

        HierarchicalContextState state = assembler.loadAndEstimate(request, POLICY);
        PreparedConversationContext prepared = assembler.prepareCurrentView(state);

        assertThat(prepared.strategyId()).isEqualTo("hierarchical");
        assertThat(prepared.compacted()).isFalse();
        assertThat(prepared.strategyGeneration()).isZero();
        assertThat(prepared.messages())
                .containsExactlyElementsOf(
                        concat(
                                request.fixedSystemMessages(),
                                turns.stream().flatMap(turn -> turn.messages().stream()).toList()));
        assertThat(prepared.estimatedInputTokens()).isEqualTo(state.estimatedTokens());
    }

    @Test
    void publishedViewUsesColdFrontierHotAndCurrentWithoutOverlap() {
        ConversationContextRequest request = request(new ContextBudget(4_096, 256, 128, 11));
        List<ConversationTurn> turns = turns(6, true);
        HierarchicalCheckpoint checkpoint =
                checkpoint(
                        4L,
                        2L,
                        COLD_SUMMARY,
                        "{\"schemaVersion\":1,\"nodes\":[\"warm-1\",\"warm-2\"]}",
                        8L);
        HierarchicalSummaryNode warmTwo = node("warm-2", 0, 7L, 8L, WARM_TWO_SUMMARY);
        HierarchicalSummaryNode warmOne = node("warm-1", 0, 5L, 6L, WARM_ONE_SUMMARY);
        when(history.load(request.execution())).thenReturn(turns);
        when(nodes.findByIds(SESSION_ID, List.of("warm-1", "warm-2")))
                .thenReturn(List.of(warmTwo, warmOne));

        PreparedConversationContext prepared =
                assembler.preparePublishedView(checkpoint, request, POLICY);

        assertThat(prepared.strategyGeneration()).isEqualTo(8L);
        assertThat(prepared.compacted()).isTrue();
        assertThat(prepared.messages().stream().map(ContextMessage::content).toList())
                .containsSubsequence(
                        "fixed-system",
                        WARM_ONE_SUMMARY,
                        WARM_TWO_SUMMARY,
                        "user-5",
                        "assistant-5",
                        "user-6")
                .doesNotContain(
                        "user-1",
                        "assistant-1",
                        "user-2",
                        "assistant-2",
                        "user-3",
                        "assistant-3",
                        "user-4",
                        "assistant-4");
        assertThat(prepared.messages().get(1).role()).isEqualTo(ContextMessage.Role.SYSTEM);
        assertThat(prepared.messages().get(1).content()).contains(COLD_SUMMARY);
    }

    @Test
    void protectedMinimumAboveTargetKeepsTenHotTurnsRawAndReportsTargetNotReached() {
        ConversationContextRequest request = request(new ContextBudget(2_000, 100, 100, 1_350));
        List<ConversationTurn> turns = turns(11, true);
        HierarchicalCheckpoint checkpoint = checkpoint(0L, 0L, EMPTY_SUMMARY, EMPTY_FRONTIER, 3L);
        when(history.load(request.execution())).thenReturn(turns);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(checkpoint);

        HierarchicalContextState state = assembler.loadAndEstimate(request, POLICY);
        HierarchicalConversationContextAssembler.CompactionInput input =
                assembler.toCompactionInput(state);
        PreparedConversationContext prepared =
                assembler.preparePublishedView(checkpoint, request, POLICY);

        assertThat(state.estimatedTokens())
                .isGreaterThan(POLICY.targetInputTokens(request.budget()))
                .isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
        assertThat(input.targetReached()).isFalse();
        assertThat(input.planningInput().estimatedInputTokens()).isEqualTo(state.estimatedTokens());
        assertThat(input.plan().hotDegradationRanges()).isEmpty();
        assertThat(prepared.messages())
                .containsSubsequence(
                        turns.getFirst().messages().getFirst(),
                        turns.getFirst().messages().getLast(),
                        turns.get(9).messages().getFirst());
    }

    @Test
    void hardBreachPlansOldestFirstHotDegradationAndPublishedNodeReplacesItsRawTurn() {
        ConversationContextRequest request = request(new ContextBudget(2_000, 100, 100, 1_000));
        List<ConversationTurn> turns = turnsWithLargeOldestHot();
        HierarchicalCheckpoint before = checkpoint(0L, 0L, EMPTY_SUMMARY, EMPTY_FRONTIER, 3L);
        when(history.load(request.execution())).thenReturn(turns);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(before);

        HierarchicalContextState state = assembler.loadAndEstimate(request, POLICY);
        HierarchicalConversationContextAssembler.CompactionInput input =
                assembler.toCompactionInput(state);

        assertThat(state.estimatedTokens()).isGreaterThan(request.budget().hardInputLimitTokens());
        assertThat(input.plan().hotDegradationRanges())
                .extracting(range -> range.startTurn())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        HierarchicalCheckpoint after =
                checkpoint(
                        0L,
                        0L,
                        EMPTY_SUMMARY,
                        "{\"schemaVersion\":1,\"nodes\":[\"emergency-1\"]}",
                        4L);
        HierarchicalSummaryNode emergency = node("emergency-1", 0, 1L, 2L, EMERGENCY_SUMMARY);
        when(nodes.findByIds(SESSION_ID, List.of("emergency-1"))).thenReturn(List.of(emergency));

        PreparedConversationContext prepared =
                assembler.preparePublishedView(after, request, POLICY);

        assertThat(prepared.messages().stream().map(ContextMessage::content).toList())
                .contains(EMERGENCY_SUMMARY, "user-2", "assistant-2")
                .doesNotContain(turns.getFirst().messages().getFirst().content(), "assistant-1");
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
    }

    @Test
    void fixedFrameworkAndCurrentIncompleteTurnAboveHardLimitThrowsStableWindowError() {
        ConversationContextRequest request = request(new ContextBudget(1_000, 100, 100, 790));
        List<ConversationTurn> turns = List.of(incompleteTurn(1L, 1L, "current-user"));
        HierarchicalCheckpoint checkpoint = checkpoint(0L, 0L, EMPTY_SUMMARY, EMPTY_FRONTIER, 0L);
        when(history.load(request.execution())).thenReturn(turns);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(checkpoint);

        assertThatThrownBy(() -> assembler.loadAndEstimate(request, POLICY))
                .isInstanceOf(HierarchicalContextWindowExceededException.class)
                .hasMessage("CONTEXT_WINDOW_EXCEEDED");
    }

    @Test
    void publishedViewRejectsMalformedFrontierAndUnauthorizedNodeResults() {
        ConversationContextRequest request = request(new ContextBudget(4_096, 256, 128, 11));
        List<ConversationTurn> turns = turns(3, false);
        when(history.load(request.execution())).thenReturn(turns);

        for (String malformed :
                List.of(
                        "{\"schemaVersion\":2,\"nodes\":[]}",
                        "{\"schemaVersion\":1,\"nodes\":[\"\"]}",
                        "{\"schemaVersion\":1,\"nodes\":[\"n\",\"n\"]}",
                        "{\"schemaVersion\":1,\"nodes\":[],\"extra\":true}")) {
            HierarchicalCheckpoint checkpoint = checkpoint(0L, 0L, EMPTY_SUMMARY, malformed, 1L);
            assertThatThrownBy(() -> assembler.preparePublishedView(checkpoint, request, POLICY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Hierarchical published frontier is inconsistent");
        }

        HierarchicalCheckpoint checkpoint =
                checkpoint(
                        0L, 0L, EMPTY_SUMMARY, "{\"schemaVersion\":1,\"nodes\":[\"wanted\"]}", 1L);
        when(nodes.findByIds(SESSION_ID, List.of("wanted")))
                .thenReturn(List.of(node("extra", 0, 1L, 2L, WARM_ONE_SUMMARY)));

        assertThatThrownBy(() -> assembler.preparePublishedView(checkpoint, request, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical published frontier is inconsistent");
    }

    @Test
    void publishedViewRejectsNodeIdentityMismatchAndGapOrParentDescendantOverlap() {
        ConversationContextRequest request = request(new ContextBudget(4_096, 256, 128, 11));
        List<ConversationTurn> turns = turns(5, false);
        when(history.load(request.execution())).thenReturn(turns);
        HierarchicalCheckpoint checkpoint =
                checkpoint(
                        0L,
                        0L,
                        EMPTY_SUMMARY,
                        "{\"schemaVersion\":1,\"nodes\":[\"one\",\"three\"]}",
                        1L);
        HierarchicalSummaryNode one = node("one", 0, 1L, 2L, WARM_ONE_SUMMARY);
        HierarchicalSummaryNode three = node("three", 0, 5L, 6L, WARM_TWO_SUMMARY);
        when(nodes.findByIds(SESSION_ID, List.of("one", "three"))).thenReturn(List.of(one, three));

        assertThatThrownBy(() -> assembler.preparePublishedView(checkpoint, request, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical published frontier is inconsistent");

        HierarchicalSummaryNode wrongIdentity =
                new HierarchicalSummaryNode(
                        "one",
                        SESSION_ID,
                        0,
                        1L,
                        1L,
                        2L,
                        null,
                        null,
                        WARM_ONE_SUMMARY,
                        20,
                        1,
                        1,
                        "other-provider",
                        MODEL_NAME);
        when(nodes.findByIds(SESSION_ID, List.of("one", "three")))
                .thenReturn(List.of(wrongIdentity, three));

        assertThatThrownBy(() -> assembler.preparePublishedView(checkpoint, request, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical published frontier is inconsistent");

        HierarchicalCheckpoint overlappingCheckpoint =
                checkpoint(
                        0L,
                        0L,
                        EMPTY_SUMMARY,
                        "{\"schemaVersion\":1,\"nodes\":[\"parent\",\"child\"]}",
                        1L);
        HierarchicalSummaryNode parent = node("parent", 1, 1L, 4L, WARM_ONE_SUMMARY);
        HierarchicalSummaryNode child = node("child", 0, 1L, 2L, WARM_TWO_SUMMARY);
        when(nodes.findByIds(SESSION_ID, List.of("parent", "child")))
                .thenReturn(List.of(parent, child));

        assertThatThrownBy(
                        () ->
                                assembler.preparePublishedView(
                                        overlappingCheckpoint, request, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical published frontier is inconsistent");
    }

    private static ConversationContextRequest request(ContextBudget budget) {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", SESSION_ID, "execution", "owner", 1L, 100L),
                budget,
                List.of(ContextMessage.system("fixed-system")),
                List.of());
    }

    private static List<ConversationTurn> turns(int count, boolean lastIncomplete) {
        List<ConversationTurn> result = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            long start = ordinal * 2L - 1L;
            if (lastIncomplete && ordinal == count) {
                result.add(incompleteTurn(ordinal, start, "user-" + ordinal));
            } else {
                result.add(
                        new ConversationTurn(
                                ordinal,
                                start,
                                start + 1L,
                                true,
                                List.of(
                                        ContextMessage.user("user-" + ordinal),
                                        ContextMessage.assistant("assistant-" + ordinal, null))));
            }
        }
        return List.copyOf(result);
    }

    private static List<ConversationTurn> turnsWithLargeOldestHot() {
        List<ConversationTurn> result = new ArrayList<>(turns(10, false));
        result.set(
                0,
                new ConversationTurn(
                        1L,
                        1L,
                        2L,
                        true,
                        List.of(
                                ContextMessage.user("x".repeat(1_000)),
                                ContextMessage.assistant("assistant-1", null))));
        return List.copyOf(result);
    }

    private static ConversationTurn incompleteTurn(long ordinal, long sequence, String content) {
        return new ConversationTurn(
                ordinal, sequence, sequence, false, List.of(ContextMessage.user(content)));
    }

    private static HierarchicalCheckpoint checkpoint(
            long pointer, long summarizedTurns, String cold, String frontier, long generation) {
        return new HierarchicalCheckpoint(
                "checkpoint",
                SESSION_ID,
                cold,
                pointer,
                summarizedTurns,
                20,
                1,
                1,
                MODEL_PROVIDER,
                MODEL_NAME,
                frontier,
                generation,
                0L);
    }

    private static HierarchicalSummaryNode node(
            String id, int level, long startSequence, long endSequence, String document) {
        return new HierarchicalSummaryNode(
                id,
                SESSION_ID,
                level,
                level == 0 ? 1L : 1L << level,
                startSequence,
                endSequence,
                level == 0 ? null : id + "-left",
                level == 0 ? null : id + "-right",
                document,
                20,
                1,
                1,
                MODEL_PROVIDER,
                MODEL_NAME);
    }

    private static List<ContextMessage> concat(
            List<ContextMessage> first, List<ContextMessage> second) {
        List<ContextMessage> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }
}
