package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** 跨模块验收 LSM 压缩触发、水位、历史重放和层级边界。 */
class LsmContextCompactionIntegrationTest {

    private static final String TENANT_ID = "tenant_acceptance";
    private static final String SESSION_ID = "session_acceptance";
    private static final String FIRST_EXECUTION_ID = "execution_first";
    private static final String SECOND_EXECUTION_ID = "execution_second";
    private static final String OWNER_ID = "owner_acceptance";
    private static final String FIRST_QUESTION = "first question";
    private static final String FIRST_ANSWER = "first answer";
    private static final String SECOND_QUESTION = "second question";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    @Test
    void secondExecutionReadsHistoryProducedByFirstExecution() {
        AgentExecutionService executions = mock(AgentExecutionService.class);
        ConversationMessageService messages = mock(ConversationMessageService.class);
        ToolExecutionService tools = mock(ToolExecutionService.class);
        LsmContextManifestRepository manifests = mock(LsmContextManifestRepository.class);
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        AgentExecution secondExecution = new AgentExecution();
        secondExecution.setExecutionId(SECOND_EXECUTION_ID);
        secondExecution.setConversationSessionId(SESSION_ID);
        when(executions.findByExecutionId(SECOND_EXECUTION_ID)).thenReturn(secondExecution);
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, 3L))
                .thenReturn(
                        List.of(
                                message(1L, FIRST_EXECUTION_ID, ROLE_USER, FIRST_QUESTION),
                                message(2L, FIRST_EXECUTION_ID, ROLE_ASSISTANT, FIRST_ANSWER),
                                message(3L, SECOND_EXECUTION_ID, ROLE_USER, SECOND_QUESTION)));
        when(manifests.findManifest(SESSION_ID)).thenReturn(java.util.Optional.empty());
        when(manifests.findOrCreate(any())).thenAnswer(call -> call.getArgument(0));
        when(segments.findBySession(SESSION_ID)).thenReturn(List.of());
        IdGenerator ids = prefix -> prefix + "-acceptance";
        LsmConversationContextAssembler assembler =
                new LsmConversationContextAssembler(
                        executions,
                        messages,
                        tools,
                        manifests,
                        segments,
                        new LsmDocumentCodec(),
                        new ConversationTokenEstimator(),
                        mock(ContextExecutionControlPort.class),
                        ids,
                        JsonMapper.builder().build());

        LsmConversationContextAssembler.ContextState state =
                assembler.loadAndEstimate(request(SECOND_EXECUTION_ID, 3L, 5, 4));

        assertThat(state.rawMessages())
                .extracting(ContextMessage::content)
                .containsExactly(FIRST_QUESTION, FIRST_ANSWER, SECOND_QUESTION);
        assertThat(state.turns()).hasSize(2);
        assertThat(state.turns().getFirst().turn().completed()).isTrue();
        assertThat(state.turns().getLast().turn().completed()).isFalse();
    }

    @Test
    void seventyFivePercentPlusOneTriggersCompactionAndReturnsAtMostTargetWatermark() {
        LsmExecutionPolicyRepository policies = mock(LsmExecutionPolicyRepository.class);
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler assembler = spy(assembler(segments));
        LsmCompactionCoordinator coordinator = mock(LsmCompactionCoordinator.class);
        ConversationContextRequest request =
                request(SECOND_EXECUTION_ID, 24L, 5, 4, new ContextBudget(1_000, 100, 50, 0));
        List<LsmConversationContextAssembler.TurnSource> turns = turnSources(12);
        LsmConversationContextAssembler.ContextState state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        turns,
                        turns.stream().flatMap(turn -> turn.turn().messages().stream()).toList(),
                        LsmContextManifest.empty(),
                        List.of(),
                        751);
        LsmCompactionCoordinator.CompactionRequest compactionRequest =
                mock(LsmCompactionCoordinator.CompactionRequest.class);
        LsmSummarySegmentEntity summarizedHistory = summarySegment("summary-1-10", 1L, 10L);
        LsmContextManifest published =
                new LsmContextManifest(
                        null,
                        List.of(summarizedHistory.getSummarySegmentId()),
                        null,
                        null,
                        null,
                        null,
                        1L,
                        5,
                        4);
        when(policies.findOrCreateExecutionPolicy(request.execution(), request.policy()))
                .thenReturn(request.policy());
        doReturn(state).when(assembler).loadAndEstimate(request);
        doReturn(compactionRequest).when(assembler).toCompactionRequest(state);
        when(coordinator.compactIfRequired(compactionRequest))
                .thenReturn(
                        new LsmCompactionCoordinator.CompactionOutcome(
                                LsmCompactionCoordinator.PublishResult.PUBLISHED, published));
        when(segments.findByIdsInOrder(published.activeSegmentIds()))
                .thenReturn(List.of(summarizedHistory));

        PreparedConversationContext prepared =
                new LsmConversationContextStrategy(policies, assembler, coordinator)
                        .prepare(request);

        assertThat(state.estimatedTokens()).isEqualTo(request.budget().softTriggerTokens() + 1);
        assertThat(prepared.compacted()).isTrue();
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(request.budget().targetInputTokens());
        verify(coordinator).compactIfRequired(compactionRequest);
    }

    @Test
    void warmPublishesWithinConfiguredRangeAndHotKeepsTenCompleteTurns() {
        LsmCompactionPlan plan =
                new LsmCompactionPlanner()
                        .plan(
                                new LsmPlanningInput(
                                        completedTurns(127),
                                        LsmContextManifest.empty(),
                                        List.of(),
                                        new LsmPlanningInput.Policy(1, 5, 4),
                                        new LsmPlanningInput.TokenPressure(20_000, 8_000, 16_000),
                                        true));

        assertThat(plan.proposedManifest().warmTurnCount()).isBetween(100L, 109L);
        assertThat(plan.hotBoundary().completedTurnCount()).isEqualTo(10);
        assertThat(plan.hotBoundary().startTurnSequence()).isEqualTo(118L);
        assertThat(plan.hotBoundary().endTurnSequence()).isEqualTo(127L);
    }

    @Test
    void configurableFanoutAndMaximumLevelProduceOneAdvancingRoot() {
        assertRootPolicy(3, 3);
        assertRootPolicy(5, 5);

        // 已有 ROOT 只滚动为一个新 ROOT，覆盖向后推进且旧 ROOT 不再活跃。
        LsmSummarySegment root = segment("root-old", LsmSummarySegment.SegmentKind.ROOT, 3, 1, 30);
        List<LsmSummarySegment> tail =
                List.of(
                        segment("tail-31", LsmSummarySegment.SegmentKind.STANDARD, 2, 31, 40),
                        segment("tail-41", LsmSummarySegment.SegmentKind.STANDARD, 2, 41, 50));
        LsmCompactionPlan rolled = planWithSegments(root, tail, 3, 3);

        assertThat(rolled.projectedSegments())
                .singleElement()
                .satisfies(
                        advanced -> {
                            assertThat(advanced.kind())
                                    .isEqualTo(LsmSummarySegment.SegmentKind.ROOT);
                            assertThat(advanced.coverage())
                                    .isEqualTo(new LsmSummarySegment.Coverage(1, 50));
                            assertThat(advanced.segmentId()).isNotEqualTo(root.segmentId());
                        });
    }

    private static void assertRootPolicy(int fanout, int maxLevel) {
        List<LsmSummarySegment> topChildren =
                IntStream.range(0, fanout)
                        .mapToObj(
                                index -> {
                                    long start = index * 10L + 1L;
                                    return segment(
                                            "child-" + fanout + "-" + start,
                                            LsmSummarySegment.SegmentKind.STANDARD,
                                            maxLevel - 1,
                                            start,
                                            start + 9L);
                                })
                        .toList();
        LsmCompactionPlan plan = planWithSegments(null, topChildren, fanout, maxLevel);

        assertThat(plan.projectedSegments())
                .singleElement()
                .satisfies(
                        root -> {
                            assertThat(root.kind()).isEqualTo(LsmSummarySegment.SegmentKind.ROOT);
                            assertThat(root.level()).isEqualTo(maxLevel);
                            assertThat(root.sourceSegmentIds()).hasSize(fanout);
                        });
    }

    private static LsmCompactionPlan planWithSegments(
            LsmSummarySegment root, List<LsmSummarySegment> tail, int fanout, int maxLevel) {
        List<LsmSummarySegment> active = new ArrayList<>();
        if (root != null) {
            active.add(root);
        }
        active.addAll(tail);
        LsmContextManifest manifest =
                new LsmContextManifest(
                        root == null ? null : root.segmentId(),
                        active.stream().map(LsmSummarySegment::segmentId).toList(),
                        null,
                        null,
                        null,
                        null,
                        0L,
                        fanout,
                        maxLevel);
        return new LsmCompactionPlanner()
                .plan(
                        new LsmPlanningInput(
                                List.of(),
                                manifest,
                                active,
                                new LsmPlanningInput.Policy(1, fanout, maxLevel),
                                new LsmPlanningInput.TokenPressure(100, 1_000, 2_000),
                                false));
    }

    private static LsmSummarySegment segment(
            String id, LsmSummarySegment.SegmentKind kind, int level, long start, long end) {
        return new LsmSummarySegment(
                id, kind, level, new LsmSummarySegment.Coverage(start, end), 100, List.of(), false);
    }

    private static ConversationContextRequest request(
            String executionId, long inputBoundary, int fanout, int maxLevel) {
        return request(
                executionId, inputBoundary, fanout, maxLevel, new ContextBudget(100, 10, 5, 0));
    }

    private static ConversationContextRequest request(
            String executionId,
            long inputBoundary,
            int fanout,
            int maxLevel,
            ContextBudget budget) {
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        TENANT_ID, SESSION_ID, executionId, OWNER_ID, 1L, inputBoundary),
                budget,
                new ContextPolicySnapshot(1, fanout, maxLevel, 10, 75, 65, 100, 109, 10),
                List.of(),
                List.of());
    }

    private static ConversationMessage message(
            long sequence, String executionId, String role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationMessageId("message-" + sequence);
        message.setConversationSessionId(SESSION_ID);
        message.setExecutionId(executionId);
        message.setConversationMessageSequence(sequence);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static List<ConversationTurn> completedTurns(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(
                        sequence ->
                                new ConversationTurn(
                                        sequence,
                                        List.of(
                                                ContextMessage.user("question-" + sequence),
                                                ContextMessage.assistant(
                                                        "answer-" + sequence, null)),
                                        true))
                .toList();
    }

    private static List<LsmConversationContextAssembler.TurnSource> turnSources(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(
                        sequence -> {
                            boolean completed = sequence < count;
                            List<ContextMessage> messages =
                                    completed
                                            ? List.of(
                                                    ContextMessage.user("question-" + sequence),
                                                    ContextMessage.assistant(
                                                            "answer-" + sequence, null))
                                            : List.of(ContextMessage.user("current-question"));
                            long startMessage = sequence * 2L - 1L;
                            long endMessage = completed ? sequence * 2L : startMessage;
                            return new LsmConversationContextAssembler.TurnSource(
                                    new ConversationTurn(sequence, messages, completed),
                                    startMessage,
                                    endMessage);
                        })
                .toList();
    }

    private static LsmSummarySegmentEntity summarySegment(String id, long start, long end) {
        LsmSummarySegmentEntity segment = new LsmSummarySegmentEntity();
        segment.setSummarySegmentId(id);
        segment.setKind(LsmSummarySegment.SegmentKind.STANDARD.name());
        segment.setLevel(0);
        segment.setStartTurnSequence(start);
        segment.setEndTurnSequence(end);
        segment.setSummaryDocument("{}");
        segment.setEstimatedTokenCount(10);
        segment.setSourceSegmentIds("[]");
        return segment;
    }

    private static LsmConversationContextAssembler assembler(LsmSummarySegmentRepository segments) {
        return new LsmConversationContextAssembler(
                null,
                null,
                null,
                null,
                segments,
                new LsmDocumentCodec(),
                new ConversationTokenEstimator(),
                mock(ContextExecutionControlPort.class),
                null,
                JsonMapper.builder().build());
    }
}
