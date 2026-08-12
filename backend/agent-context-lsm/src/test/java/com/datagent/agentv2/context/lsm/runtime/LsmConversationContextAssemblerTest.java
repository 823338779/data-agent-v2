package com.datagent.agentv2.context.lsm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LsmConversationContextAssemblerTest {

    private static final String RAW_MARKER = "raw-marker";
    private static final String ROOT_DOCUMENT = "{\"root\":true}";
    private static final String COLD_DOCUMENT = "{\"cold\":true}";
    private static final String WARM_PARENT_DOCUMENT = "{\"warm\":\"parent\"}";
    private static final String WARM_CHILD_ONE_DOCUMENT = "{\"warm\":\"child-1\"}";
    private static final String WARM_CHILD_TWO_DOCUMENT = "{\"warm\":\"child-2\"}";

    @Test
    void higherLevelCandidateReadsOrderedDirectChildSummariesWithoutRawTurns() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler assembler = assembler(segments);
        ConversationContextRequest request = request(new ContextBudget(2_000, 100, 100, 0));
        List<LsmConversationContextAssembler.TurnSource> turns = turns(41);
        List<LsmSummarySegmentEntity> persisted =
                List.of(
                        segment("child-1", "STANDARD", 0, 1, 10, 80, "{\"child\":1}", "[]"),
                        segment("child-2", "STANDARD", 0, 11, 20, 80, "{\"child\":2}", "[]"),
                        segment("child-3", "STANDARD", 0, 21, 30, 80, "{\"child\":3}", "[]"));
        LsmContextManifest manifest =
                new LsmContextManifest(
                        null, List.of("child-1", "child-2", "child-3"), 31L, 31L, 1L, 31L, 0, 3, 2);
        var state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        turns,
                        turns.stream().flatMap(turn -> turn.turn().messages().stream()).toList(),
                        manifest,
                        persisted,
                        1_600);

        CandidateInput parent =
                assembler.toCompactionRequest(state).candidateInputs().stream()
                        .filter(candidate -> candidate.generationRequest().level() > 0)
                        .findFirst()
                        .orElseThrow();

        assertThat(parent.generationRequest().sourceDocument())
                .contains("\"child\":1", "\"child\":2", "\"child\":3")
                .doesNotContain(RAW_MARKER);
    }

    @Test
    void warmFrontierExpandsToChildrenWithRoomAndUsesParentWhenBudgetIsTight() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler delegate = assembler(segments);
        LsmConversationContextAssembler assembler = org.mockito.Mockito.spy(delegate);
        LsmContextManifest manifest =
                new LsmContextManifest(
                        "root",
                        List.of("root", "cold", "warm-parent"),
                        null,
                        null,
                        21L,
                        40L,
                        3,
                        3,
                        2);
        List<LsmSummarySegmentEntity> active =
                List.of(
                        segment("root", "ROOT", 2, 1, 10, 20, ROOT_DOCUMENT, "[]"),
                        segment("cold", "STANDARD", 1, 11, 20, 20, COLD_DOCUMENT, "[]"),
                        segment(
                                "warm-parent",
                                "STANDARD",
                                1,
                                21,
                                40,
                                20,
                                WARM_PARENT_DOCUMENT,
                                "[\"warm-child-1\",\"warm-child-2\"]"));
        List<LsmSummarySegmentEntity> all =
                List.of(
                        active.get(0),
                        active.get(1),
                        segment(
                                "warm-child-1",
                                "STANDARD",
                                0,
                                21,
                                30,
                                80,
                                WARM_CHILD_ONE_DOCUMENT,
                                "[]"),
                        segment(
                                "warm-child-2",
                                "STANDARD",
                                0,
                                31,
                                40,
                                80,
                                WARM_CHILD_TWO_DOCUMENT,
                                "[]"),
                        active.get(2));
        when(segments.findByIdsInOrder(manifest.activeSegmentIds())).thenReturn(active);
        when(segments.findBySession("session")).thenReturn(all);

        ConversationContextRequest roomy = request(new ContextBudget(2_000, 100, 100, 0));
        ConversationContextRequest tight = request(new ContextBudget(600, 50, 50, 0));
        var roomyState = publishedState(roomy, manifest, all);
        var tightState = publishedState(tight, manifest, all);
        doReturn(roomyState).when(assembler).loadAndEstimate(roomy);
        doReturn(tightState).when(assembler).loadAndEstimate(tight);

        List<String> roomyContents =
                assembler.preparePublishedView(manifest, roomy).messages().stream()
                        .map(ContextMessage::content)
                        .toList();
        List<String> tightContents =
                assembler.preparePublishedView(manifest, tight).messages().stream()
                        .map(ContextMessage::content)
                        .toList();

        assertThat(roomyContents)
                .containsSubsequence(
                        "fixed-system",
                        ROOT_DOCUMENT,
                        COLD_DOCUMENT,
                        WARM_CHILD_ONE_DOCUMENT,
                        WARM_CHILD_TWO_DOCUMENT,
                        "hot",
                        "current")
                .doesNotContain(WARM_PARENT_DOCUMENT);
        assertThat(tightContents)
                .containsSubsequence(
                        "fixed-system",
                        ROOT_DOCUMENT,
                        COLD_DOCUMENT,
                        WARM_PARENT_DOCUMENT,
                        "hot",
                        "current")
                .doesNotContain(WARM_CHILD_ONE_DOCUMENT, WARM_CHILD_TWO_DOCUMENT);
    }

    @Test
    void rejectsNonContinuousManifestStorageFrontier() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler delegate = assembler(segments);
        LsmConversationContextAssembler assembler = org.mockito.Mockito.spy(delegate);
        ConversationContextRequest request = request(new ContextBudget(2_000, 100, 100, 0));
        LsmContextManifest manifest =
                new LsmContextManifest(
                        null, List.of("one", "three"), null, null, null, null, 1, 3, 2);
        List<LsmSummarySegmentEntity> active =
                List.of(
                        segment("one", "STANDARD", 0, 1, 10, 20, "{\"one\":true}", "[]"),
                        segment("three", "STANDARD", 0, 21, 30, 20, "{\"three\":true}", "[]"));
        when(segments.findByIdsInOrder(manifest.activeSegmentIds())).thenReturn(active);
        doReturn(publishedState(request, manifest, active))
                .when(assembler)
                .loadAndEstimate(request);

        assertThatThrownBy(() -> assembler.preparePublishedView(manifest, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LSM manifest active segment set is inconsistent");
    }

    private static LsmConversationContextAssembler.ContextState publishedState(
            ConversationContextRequest request,
            LsmContextManifest manifest,
            List<LsmSummarySegmentEntity> persisted) {
        ContextMessage hot = ContextMessage.user("hot");
        ContextMessage current = ContextMessage.user("current");
        List<LsmConversationContextAssembler.TurnSource> turns =
                List.of(
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(41, List.of(hot), true), 41, 41),
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(42, List.of(current), false), 42, 42));
        return new LsmConversationContextAssembler.ContextState(
                request, turns, List.of(hot, current), manifest, persisted, 500);
    }

    private static List<LsmConversationContextAssembler.TurnSource> turns(int count) {
        List<LsmConversationContextAssembler.TurnSource> result = new ArrayList<>();
        for (int sequence = 1; sequence <= count; sequence++) {
            ContextMessage message = ContextMessage.user(RAW_MARKER + "-" + sequence);
            result.add(
                    new LsmConversationContextAssembler.TurnSource(
                            new ConversationTurn(sequence, List.of(message), true),
                            sequence,
                            sequence));
        }
        return result;
    }

    private static LsmSummarySegmentEntity segment(
            String id,
            String kind,
            int level,
            long start,
            long end,
            int tokens,
            String document,
            String sourceIds) {
        LsmSummarySegmentEntity entity = new LsmSummarySegmentEntity();
        entity.setSummarySegmentId(id);
        entity.setConversationSessionId("session");
        entity.setKind(kind);
        entity.setLevel(level);
        entity.setStartTurnSequence(start);
        entity.setEndTurnSequence(end);
        entity.setStartMessageSequence(start);
        entity.setEndMessageSequence(end);
        entity.setEstimatedTokenCount(tokens);
        entity.setSummaryDocument(document);
        entity.setSourceSegmentIds(sourceIds);
        return entity;
    }

    private static ConversationContextRequest request(ContextBudget budget) {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", "owner", 1, 42),
                budget,
                new ContextPolicySnapshot(1, 3, 2, 10, 75, 65, 100, 109, 10),
                List.of(ContextMessage.system("fixed-system")),
                List.of());
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
