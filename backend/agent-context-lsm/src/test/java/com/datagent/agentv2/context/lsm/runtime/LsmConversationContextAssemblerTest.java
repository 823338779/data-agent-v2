package com.datagent.agentv2.context.lsm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.RuntimeContextContribution;
import com.datagent.agentv2.conversation.context.RuntimeContextInput;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class LsmConversationContextAssemblerTest {

    private static final String RAW_MARKER = "raw-marker";
    private static final String ROOT_DOCUMENT = "{\"root\":true}";
    private static final String COLD_DOCUMENT = "{\"cold\":true}";
    private static final String WARM_PARENT_DOCUMENT = "{\"warm\":\"parent\"}";
    private static final String WARM_CHILD_ONE_DOCUMENT = "{\"warm\":\"child-1\"}";
    private static final String WARM_CHILD_TWO_DOCUMENT = "{\"warm\":\"child-2\"}";
    private static final String ROOT_WARM_CHILD_DOCUMENT = "{\"root\":\"warm-child\"}";
    private static final String READY_ORPHAN_DOCUMENT = "{\"orphan\":\"ready\"}";
    private static final String CANCELLED_ORPHAN_DOCUMENT = "{\"orphan\":\"cancelled\"}";
    private static final String SUPERSEDED_ORPHAN_DOCUMENT = "{\"orphan\":\"superseded\"}";
    private static final String CAS_LOSER_ORPHAN_DOCUMENT = "{\"orphan\":\"cas-loser\"}";

    @Test
    void hardBoundaryKeepsNecessaryMessagesAndOmitsOptionalMemory() {
        var base = request(new ContextBudget(2000, 1, 0, 0));
        var raw = List.of(ContextMessage.user("latest"));
        int necessary =
                new ConversationTokenEstimator().estimateCompleteInput(base, raw)
                        + new ConversationTokenEstimator()
                                .estimateMessages(
                                        List.of(ContextMessage.user("evidence_id=real-user")));
        var tight = withMemory(request(new ContextBudget(necessary + 1, 1, 0, 0)));
        var turns =
                List.of(
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(1, raw, false), 1, 1));
        var state =
                new LsmConversationContextAssembler.ContextState(
                        tight,
                        policy(),
                        turns,
                        raw,
                        new LsmContextManifest(null, List.of(), 1L, 1L, 0, 3, 2),
                        List.of(),
                        necessary);
        var prepared = assembler(mock(LsmSummarySegmentRepository.class)).prepareCurrentView(state);
        assertThat(prepared.estimatedInputTokens())
                .isEqualTo(tight.budget().hardInputLimitTokens());
        assertThat(prepared.runtimeStatistics().injected()).isZero();
        assertThat(prepared.messages()).containsAll(raw);
    }

    @Test
    void loadsPersistentMessagesWithoutChangingThemAndBudgetsOnlySelectedRuntimeCost() {
        var executions = mock(AgentExecutionService.class);
        var messages = mock(ConversationMessageService.class);
        var manifests = mock(LsmContextManifestRepository.class);
        var execution =
                AgentExecution.running(
                        "execution", "session", "user", "key", "hash", java.time.Instant.MAX);
        when(executions.findByExecutionId("execution")).thenReturn(execution);
        var rows = new ArrayList<ConversationMessage>();
        for (int i = 1; i <= 21; i++) {
            rows.add(
                    ConversationMessage.user(
                            "u" + i, "session", "old".repeat(20), i * 2 - 1, "user"));
            rows.add(
                    ConversationMessage.assistant(
                            "a" + i, "session", "execution", "answer", i * 2));
        }
        when(messages.findByConversationSessionIdUpTo("session", 42)).thenReturn(rows);
        var manifest = new LsmContextManifestEntity();
        manifest.setActiveSegmentIds("[]");
        manifest.setGeneration(0L);
        manifest.setEffectivePolicyVersion(1);
        manifest.setEffectiveFanout(3);
        manifest.setEffectiveMaxLevel(2);
        when(manifests.findManifest("session")).thenReturn(java.util.Optional.of(manifest));
        var assembler =
                new LsmConversationContextAssembler(
                        executions,
                        messages,
                        manifests,
                        mock(LsmSummarySegmentRepository.class),
                        new LsmDocumentCodec(),
                        new ConversationTokenEstimator(),
                        mock(ContextExecutionControlPort.class),
                        null,
                        JsonMapper.builder().build(),
                        new com.datagent.agentv2.context.lsm.persistence.mapping
                                .LsmPersistenceStructMapperImpl());
        var base = request(new ContextBudget(2800, 1, 0, 0));
        var before = rows.stream().map(ConversationMessage::getContent).toList();
        var rawState = assembler.loadAndEstimate(base, policy());
        var enriched = withMemory(base);
        var state = assembler.loadAndEstimate(enriched, policy());
        var prepared = assembler.prepareCurrentView(state);
        assertThat(state.estimatedTokens()).isEqualTo(prepared.estimatedInputTokens());
        assertThat(prepared.runtimeStatistics().injected()).isEqualTo(1);
        var planning = assembler.toCompactionRequest(state);
        int runtimeCost = state.estimatedTokens() - rawState.estimatedTokens();
        assertThat(planning.planningInput().historyMemoryBudget())
                .isEqualTo(
                        assembler
                                        .toCompactionRequest(rawState)
                                        .planningInput()
                                        .historyMemoryBudget()
                                - runtimeCost);
        assertThat(planning.candidateInputs())
                .isNotEmpty()
                .allSatisfy(
                        c ->
                                assertThat(c.generationRequest().sourceDocument())
                                        .doesNotContain("用户主要使用中文", "evidence_id"));
        assertThat(rows)
                .extracting(ConversationMessage::getContent)
                .containsExactlyElementsOf(before);
        Mockito.verify(messages, Mockito.times(2)).findByConversationSessionIdUpTo("session", 42);
        Mockito.verifyNoMoreInteractions(messages);
    }

    @Test
    void personalMemoryIsTransientUserContextWithPairedToolsAndFinalStatistics() {
        var enriched = withMemory(request(new ContextBudget(2000, 100, 100, 0)));
        var call =
                new ContextMessage(
                        ContextMessage.Role.ASSISTANT,
                        "",
                        null,
                        List.of(new ContextToolCall("call", "tool", Map.of())),
                        null,
                        null);
        var result = ContextMessage.tool("result", "call", "tool");
        var raw = List.of(ContextMessage.user("latest"), call, result);
        var turns =
                List.of(
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(1, raw, false), 1, 2));
        var state =
                new LsmConversationContextAssembler.ContextState(
                        enriched,
                        policy(),
                        turns,
                        raw,
                        new LsmContextManifest(null, List.of(), 1L, 1L, 0, 3, 2),
                        List.of(),
                        0);
        var prepared = assembler(mock(LsmSummarySegmentRepository.class)).prepareCurrentView(state);
        assertThat(prepared.messages())
                .extracting(ContextMessage::content)
                .containsExactly(
                        "fixed-system",
                        "evidence_id=real-user",
                        "历史数据\n用户主要使用中文",
                        "latest",
                        "",
                        "result");
        assertThat(prepared.messages().get(2).role()).isEqualTo(ContextMessage.Role.USER);
        assertThat(prepared.messages()).containsSubsequence(call, result);
        assertThat(state.rawMessages()).isEqualTo(raw);
        assertThat(state.turns()).isEqualTo(turns);
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(enriched.budget().hardInputLimitTokens());
        assertThat(prepared.runtimeStatistics().injected()).isEqualTo(1);
        assertThat(prepared.runtimeStatistics().omitted()).isZero();
        assertThat(prepared.runtimeStatistics().contributionTokens())
                .isEqualTo(
                        new ConversationTokenEstimator()
                                .estimateMessages(List.of(prepared.messages().get(2))));
    }

    @Test
    void optionalMemoryCannotTriggerCompressionOrPolluteCompactionSources() {
        var base = request(new ContextBudget(2000, 100, 100, 0));
        var turns = turns(41);
        var raw = turns.stream().flatMap(t -> t.turn().messages().stream()).toList();
        int necessary =
                new ConversationTokenEstimator().estimateCompleteInput(base, raw)
                        + new ConversationTokenEstimator()
                                .estimateMessages(
                                        List.of(ContextMessage.user("evidence_id=real-user")));
        var tight =
                withMemory(request(new ContextBudget((int) Math.ceil(necessary / .75), 1, 0, 0)));
        var state =
                new LsmConversationContextAssembler.ContextState(
                        tight,
                        policy(),
                        turns,
                        raw,
                        new LsmContextManifest(null, List.of(), 1L, 1L, 0, 3, 2),
                        List.of(),
                        necessary);
        var assembler = assembler(mock(LsmSummarySegmentRepository.class));
        var prepared = assembler.prepareCurrentView(state);
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(policy().softTriggerTokens(tight.budget()));
        assertThat(prepared.runtimeStatistics().injected()).isZero();
        assertThat(prepared.runtimeStatistics().omitted()).isEqualTo(1);
        assertThat(prepared.messages()).containsAll(raw);
        var metadataOnly =
                new ConversationContextRequest(
                        tight.execution(),
                        tight.budget(),
                        tight.fixedSystemMessages(),
                        tight.toolDefinitions(),
                        new RuntimeContextInput(tight.runtimeInput().metadata(), List.of()));
        var withoutOptional =
                new LsmConversationContextAssembler.ContextState(
                        metadataOnly, policy(), turns, raw, state.manifest(), List.of(), necessary);
        assertThat(assembler.toCompactionRequest(state).planningInput().historyMemoryBudget())
                .isEqualTo(
                        assembler
                                .toCompactionRequest(withoutOptional)
                                .planningInput()
                                .historyMemoryBudget());

        assertThat(assembler.toCompactionRequest(state).candidateInputs())
                .isNotEmpty()
                .allSatisfy(
                        candidate ->
                                assertThat(candidate.generationRequest().sourceDocument())
                                        .doesNotContain("用户主要使用中文", "evidence_id"));
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
                        null, List.of("child-1", "child-2", "child-3"), 31L, 31L, 0, 3, 2);
        var state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        policy(),
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
                .contains("child-1", "child-2", "child-3")
                .doesNotContain(RAW_MARKER);
    }

    @Test
    void publishedViewUsesOnlyAuthorizedActiveSegmentsForEveryBudget() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler delegate = assembler(segments);
        LsmConversationContextAssembler assembler = Mockito.spy(delegate);
        LsmContextManifest manifest =
                new LsmContextManifest(
                        "root", List.of("root", "cold", "warm-parent"), null, null, 3, 3, 2);
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
        doReturn(roomyState).when(assembler).loadAndEstimate(roomy, policy());
        doReturn(tightState).when(assembler).loadAndEstimate(tight, policy());

        List<String> roomyContents =
                assembler.preparePublishedView(manifest, roomy, policy()).messages().stream()
                        .map(ContextMessage::content)
                        .toList();
        List<String> tightContents =
                assembler.preparePublishedView(manifest, tight, policy()).messages().stream()
                        .map(ContextMessage::content)
                        .toList();

        assertThat(roomyContents)
                .containsSubsequence(
                        "fixed-system",
                        "[事件] root｜进行中\n事实：root",
                        "[事件] cold｜进行中\n事实：cold",
                        "[事件] warm-parent｜进行中\n事实：warm-parent",
                        "hot",
                        "current")
                .noneMatch(content -> content.contains("schemaVersion"));
        assertThat(tightContents).isEqualTo(roomyContents);
    }

    @Test
    void rootAndTailUseOneNonOverlappingAuthorizedFrontier() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler delegate = assembler(segments);
        LsmConversationContextAssembler assembler = Mockito.spy(delegate);
        ConversationContextRequest request = request(new ContextBudget(2_000, 100, 100, 0));
        LsmContextManifest manifest =
                new LsmContextManifest("root", List.of("root", "warm-parent"), null, null, 3, 3, 2);
        LsmSummarySegmentEntity root =
                segment(
                        "root",
                        "ROOT",
                        2,
                        1,
                        30,
                        20,
                        ROOT_DOCUMENT,
                        "[\"root-cold-child\",\"root-warm-child\"]");
        LsmSummarySegmentEntity warmParent =
                segment(
                        "warm-parent",
                        "STANDARD",
                        1,
                        31,
                        40,
                        20,
                        WARM_PARENT_DOCUMENT,
                        "[\"warm-child\"]");
        List<LsmSummarySegmentEntity> active = List.of(root, warmParent);
        List<LsmSummarySegmentEntity> authorized =
                List.of(
                        root,
                        segment("root-cold-child", "STANDARD", 1, 1, 20, 40, COLD_DOCUMENT, "[]"),
                        segment(
                                "root-warm-child",
                                "STANDARD",
                                1,
                                21,
                                30,
                                40,
                                ROOT_WARM_CHILD_DOCUMENT,
                                "[]"),
                        warmParent,
                        segment(
                                "warm-child",
                                "STANDARD",
                                0,
                                31,
                                40,
                                40,
                                WARM_CHILD_ONE_DOCUMENT,
                                "[]"));
        when(segments.findByIdsInOrder(manifest.activeSegmentIds())).thenReturn(active);
        doReturn(publishedState(request, manifest, authorized))
                .when(assembler)
                .loadAndEstimate(request, policy());

        List<String> contents =
                assembler.preparePublishedView(manifest, request, policy()).messages().stream()
                        .map(ContextMessage::content)
                        .toList();

        assertThat(contents)
                .containsSubsequence(
                        "fixed-system",
                        "[事件] root｜进行中\n事实：root",
                        "[事件] warm-parent｜进行中\n事实：warm-parent",
                        "hot",
                        "current")
                .noneMatch(
                        content ->
                                content.contains("root-warm-child")
                                        || content.contains("warm-child"));
    }

    @Test
    void inputFrontierRejectsReadyCancelledSupersededAndCasLoserOrphans() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler delegate = assembler(segments);
        LsmConversationContextAssembler assembler = Mockito.spy(delegate);
        ConversationContextRequest request = request(new ContextBudget(600, 50, 50, 0));
        LsmContextManifest manifest =
                new LsmContextManifest(null, List.of("warm-parent"), null, null, 3, 3, 2);
        LsmSummarySegmentEntity warmParent =
                segment(
                        "warm-parent",
                        "STANDARD",
                        1,
                        1,
                        20,
                        20,
                        WARM_PARENT_DOCUMENT,
                        "[\"warm-child-1\",\"warm-child-2\"]");
        List<LsmSummarySegmentEntity> active = List.of(warmParent);
        List<LsmSummarySegmentEntity> allPersisted =
                List.of(
                        warmParent,
                        segment(
                                "warm-child-1",
                                "STANDARD",
                                0,
                                1,
                                10,
                                80,
                                WARM_CHILD_ONE_DOCUMENT,
                                "[]"),
                        segment(
                                "warm-child-2",
                                "STANDARD",
                                0,
                                11,
                                20,
                                80,
                                WARM_CHILD_TWO_DOCUMENT,
                                "[]"),
                        segment(
                                "ready-orphan",
                                "STANDARD",
                                5,
                                1,
                                20,
                                1,
                                READY_ORPHAN_DOCUMENT,
                                "[\"warm-child-1\",\"warm-child-2\"]"),
                        segment(
                                "cancelled-orphan",
                                "STANDARD",
                                4,
                                1,
                                20,
                                2,
                                CANCELLED_ORPHAN_DOCUMENT,
                                "[\"warm-child-1\",\"warm-child-2\"]"),
                        segment(
                                "superseded-orphan",
                                "STANDARD",
                                3,
                                1,
                                20,
                                3,
                                SUPERSEDED_ORPHAN_DOCUMENT,
                                "[\"warm-child-1\",\"warm-child-2\"]"),
                        segment(
                                "cas-loser-orphan",
                                "STANDARD",
                                2,
                                1,
                                20,
                                4,
                                CAS_LOSER_ORPHAN_DOCUMENT,
                                "[\"warm-child-1\",\"warm-child-2\"]"));
        when(segments.findByIdsInOrder(manifest.activeSegmentIds())).thenReturn(active);
        doReturn(publishedState(request, manifest, allPersisted))
                .when(assembler)
                .loadAndEstimate(request, policy());

        List<String> contents =
                assembler.preparePublishedView(manifest, request, policy()).messages().stream()
                        .map(ContextMessage::content)
                        .toList();

        assertThat(contents)
                .contains("[事件] warm-parent｜进行中\n事实：warm-parent")
                .noneMatch(content -> content.contains("orphan"));
    }

    @Test
    void rejectsNonContinuousManifestStorageFrontier() {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmConversationContextAssembler delegate = assembler(segments);
        LsmConversationContextAssembler assembler = Mockito.spy(delegate);
        ConversationContextRequest request = request(new ContextBudget(2_000, 100, 100, 0));
        LsmContextManifest manifest =
                new LsmContextManifest(null, List.of("one", "three"), null, null, 1, 3, 2);
        List<LsmSummarySegmentEntity> active =
                List.of(
                        segment("one", "STANDARD", 0, 1, 10, 20, "{\"one\":true}", "[]"),
                        segment("three", "STANDARD", 0, 21, 30, 20, "{\"three\":true}", "[]"));
        when(segments.findByIdsInOrder(manifest.activeSegmentIds())).thenReturn(active);
        doReturn(publishedState(request, manifest, active))
                .when(assembler)
                .loadAndEstimate(request, policy());

        assertThatThrownBy(() -> assembler.preparePublishedView(manifest, request, policy()))
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
                request, policy(), turns, List.of(hot, current), manifest, persisted, 500);
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
        entity.setKind(LsmSummarySegment.SegmentKind.valueOf(kind));
        entity.setLevel(level);
        entity.setStartTurnSequence(start);
        entity.setEndTurnSequence(end);
        entity.setStartMessageSequence(start);
        entity.setEndMessageSequence(end);
        entity.setEstimatedTokenCount(tokens);
        entity.setSummaryDocument(eventSummary(id, start, end));
        entity.setSourceSegmentIds(sourceIds);
        return entity;
    }

    private static String eventSummary(String title, long start, long end) {
        return """
                {"schemaVersion":2,"coverage":{"startTurnSequence":%d,"endTurnSequence":%d,"startMessageSequence":%d,"endMessageSequence":%d},"events":[{"title":{"text":"%s","sourceRanges":[{"startMessageSequence":%d,"endMessageSequence":%d}]},"status":{"value":"ACTIVE","sourceRanges":[{"startMessageSequence":%d,"endMessageSequence":%d}]},"confirmedFacts":[{"statement":"%s","sourceRanges":[{"startMessageSequence":%d,"endMessageSequence":%d}]}],"userPreferences":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"openQuestions":[],"nextSteps":[]}]}
                """
                .formatted(
                        start, end, start, end, title, start, end, start, end, title, start, end);
    }

    private static ConversationContextRequest request(ContextBudget budget) {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", 42),
                budget,
                List.of(ContextMessage.system("fixed-system")),
                List.of());
    }

    private static LsmContextPolicySnapshot policy() {
        return new LsmContextPolicySnapshot(1, 3, 2);
    }

    private static LsmConversationContextAssembler assembler(LsmSummarySegmentRepository segments) {
        return new LsmConversationContextAssembler(
                null,
                null,
                null,
                segments,
                new LsmDocumentCodec(),
                new ConversationTokenEstimator(),
                mock(ContextExecutionControlPort.class),
                null,
                JsonMapper.builder().build(),
                new com.datagent.agentv2.context.lsm.persistence.mapping
                        .LsmPersistenceStructMapperImpl());
    }
}
