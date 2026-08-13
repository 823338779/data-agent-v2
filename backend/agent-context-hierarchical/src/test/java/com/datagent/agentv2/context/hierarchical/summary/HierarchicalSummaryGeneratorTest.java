package com.datagent.agentv2.context.hierarchical.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.ColdSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class HierarchicalSummaryGeneratorTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String SESSION_ID = "session-1";
    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "summary-model";
    private static final int MAX_SUMMARY_TOKENS = 1_024;
    private static final int SOURCE_SUMMARY_TOKENS = 300;
    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock private ContextSummaryModelPort model;
    @Mock private ConversationTokenEstimator estimator;

    private HierarchicalSummaryGenerator generator;

    @BeforeEach
    void setUp() {
        lenient()
                .when(model.identity())
                .thenReturn(new ContextSummaryModelIdentity(PROVIDER, MODEL));
        lenient().when(estimator.estimateSummary(any())).thenReturn(SOURCE_SUMMARY_TOKENS);
        generator =
                new HierarchicalSummaryGenerator(
                        model,
                        new ContextSummaryDocumentValidator(estimator),
                        new HierarchicalNodeKeyFactory());
    }

    @Test
    void retriesOneInvalidDocumentWithCorrectionAndReturnsSecondValidDocument() {
        when(model.summarize(any()))
                .thenReturn(
                        result("{\"confirmedFacts\":{}}", 20),
                        result(summary("metric m1 uses unit ms", 1, 2, 10, 20), 300));

        GeneratedSummary generated = generator.generate(leafRequest());

        assertThat(generated.document()).isEqualTo(summary("metric m1 uses unit ms", 1, 2, 10, 20));
        assertThat(generated.estimatedTokenCount()).isEqualTo(300);
        ArgumentCaptor<ContextSummaryRequest> requests =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model, times(2)).summarize(requests.capture());
        assertThat(requests.getAllValues().get(1).systemPrompt())
                .contains("上一次输出未通过校验")
                .doesNotContain("confirmedFacts");
    }

    @Test
    void passesTheFrozenCompleteInputHardBudgetToTheProvider() {
        GenerationRequest baseline = leafRequest();
        GenerationRequest bounded =
                new GenerationRequest(
                        baseline.tenantId(),
                        baseline.conversationSessionId(),
                        baseline.range(),
                        baseline.source(),
                        baseline.policySnapshot(),
                        baseline.maxSummaryTokens(),
                        64_000);
        when(model.summarize(any())).thenReturn(result(summary("bounded", 1, 2, 10, 20), 300));

        generator.generate(bounded);

        ArgumentCaptor<ContextSummaryRequest> request =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model).summarize(request.capture());
        assertThat(request.getValue().inputHardTokenBudget()).isEqualTo(64_000);
    }

    @Test
    void rejectsAnOversizedCompleteTurnWithoutTruncatingOrCallingTheModel() {
        GenerationRequest baseline = leafRequest();
        GenerationRequest impossible =
                new GenerationRequest(
                        baseline.tenantId(),
                        baseline.conversationSessionId(),
                        baseline.range(),
                        baseline.source(),
                        baseline.policySnapshot(),
                        baseline.maxSummaryTokens(),
                        1);

        assertThatThrownBy(() -> generator.generate(impossible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete hierarchical summary source atom");
        verify(model, never()).summarize(any());
    }

    @Test
    void checksExecutionImmediatelyBeforeEveryRetryModelCall() {
        AtomicInteger checks = new AtomicInteger();
        when(model.summarize(any()))
                .thenReturn(
                        result("{\"confirmedFacts\":{}}", 20),
                        result(summary("valid retry", 1, 2, 10, 20), 300));

        generator.generate(leafRequest(), checks::incrementAndGet);

        assertThat(checks).hasValue(2);
        verify(model, times(2)).summarize(any());
    }

    @Test
    void cancellationGuardEscapesWithoutBecomingAValidationRetry() {
        RuntimeException cancelled = new RuntimeException("cancelled");

        assertThatThrownBy(
                        () ->
                                generator.generate(
                                        leafRequest(),
                                        () -> {
                                            throw cancelled;
                                        }))
                .isSameAs(cancelled);
        verify(model, never()).summarize(any());
    }

    @Test
    void failsWithStableCodeAfterTwoInvalidAttemptsWithoutLeakingDocuments() {
        String invalid = "{\"secret-reasoning\":\"do not expose\"}";
        when(model.summarize(any())).thenReturn(result(invalid, 20), result(invalid, 20));

        assertThatThrownBy(() -> generator.generate(leafRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(HierarchicalSummaryGenerator.FAILURE_CONTEXT_COMPACTION)
                .hasMessageNotContaining("secret-reasoning");
        verify(model, times(2)).summarize(any());
    }

    @Test
    void sendsCompleteLeafMessageThinkingAndToolHistory() throws Exception {
        when(model.summarize(any()))
                .thenReturn(result(summary("metric m1 uses unit ms", 1, 2, 10, 20), 300));

        generator.generate(leafRequest());

        ArgumentCaptor<ContextSummaryRequest> request =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model).summarize(request.capture());
        JsonNode source = JSON.readTree(request.getValue().sourceDocument());
        assertThat(source).hasSize(2);
        assertThat(source.get(0).path("ordinal").asLong()).isEqualTo(1);
        assertThat(source.get(0).path("messages").get(1).path("thinking").asString())
                .isEqualTo("reason privately");
        assertThat(
                        source.get(0)
                                .path("messages")
                                .get(2)
                                .path("toolCalls")
                                .get(0)
                                .path("name")
                                .asString())
                .isEqualTo("metric_query");
        assertThat(source.get(1).path("messages").get(0).path("toolCallId").asString())
                .isEqualTo("call-1");
        assertThat(request.getValue().systemPrompt())
                .contains("事实", "假设", "决策", "排除方案")
                .contains("标识符", "日期", "指标口径", "过滤条件", "单位", "工具失败")
                .contains("不得逐字复制", "只输出符合给定 JSON Schema 的 JSON");
    }

    @Test
    void parentSourceContainsOnlyValidatedContiguousLeftThenRightSummary() throws Exception {
        when(model.summarize(any()))
                .thenReturn(result(summary("parent", 1, 2, 10, 20), SOURCE_SUMMARY_TOKENS));

        generator.generate(validParentRequest());

        ArgumentCaptor<ContextSummaryRequest> request =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model).summarize(request.capture());
        JsonNode source = JSON.readTree(request.getValue().sourceDocument());
        assertThat(source).hasSize(2);
        assertThat(source.get(0).path("confirmedFacts").get(0).path("statement").asString())
                .isEqualTo("left fact");
        assertThat(source.get(1).path("confirmedFacts").get(0).path("statement").asString())
                .isEqualTo("right fact");
    }

    @ParameterizedTest(name = "rejects untrusted parent child source: {0}")
    @ValueSource(strings = {"malformed", "unknown-version", "raw-reasoning", "wrong-coverage"})
    void validatesEveryParentChildDocumentBeforeModelInvocation(String mutation) {
        String invalidDocument = invalidSourceDocument(mutation, summary("left", 1, 1, 10, 14));
        HierarchicalSummaryNode invalidLeft = node("left", 0, 10, 14, invalidDocument);
        HierarchicalSummaryNode right = node("right", 0, 15, 20, summary("right", 2, 2, 15, 20));

        assertRejectedBeforeModel(
                GenerationRequest.parent(
                        TENANT_A,
                        SESSION_ID,
                        new GenerationRange(1, 2, 10, 20, 1),
                        invalidLeft,
                        right,
                        POLICY,
                        MAX_SUMMARY_TOKENS));
    }

    @Test
    void rejectsParentGapsPartialCoverageWrongLevelsAndIncompatibleIdentities() {
        HierarchicalSummaryNode left = node("left", 0, 10, 14, summary("left", 1, 1, 10, 14));
        HierarchicalSummaryNode right = node("right", 0, 15, 20, summary("right", 2, 2, 15, 20));
        List<GenerationRequest> invalidRequests =
                List.of(
                        parentRequest(
                                left,
                                node("gap", 0, 16, 20, summary("gap", 2, 2, 16, 20)),
                                new GenerationRange(1, 2, 10, 20, 1)),
                        parentRequest(left, right, new GenerationRange(1, 2, 9, 20, 1)),
                        parentRequest(left, right, new GenerationRange(1, 3, 10, 20, 1)),
                        parentRequest(left, right, new GenerationRange(1, 2, 10, 20, 0)),
                        parentRequest(
                                node("unaligned-left", 0, 10, 14, summary("left", 2, 2, 10, 14)),
                                node("unaligned-right", 0, 15, 20, summary("right", 3, 3, 15, 20)),
                                new GenerationRange(2, 3, 10, 20, 1)),
                        parentRequest(
                                left,
                                node("wrong-level", 1, 15, 20, summary("level", 2, 3, 15, 20)),
                                new GenerationRange(1, 2, 10, 20, 1)),
                        parentRequest(
                                nodeWithIdentity(
                                        "session",
                                        "other-session",
                                        0,
                                        10,
                                        14,
                                        summary("left", 1, 1, 10, 14),
                                        1,
                                        1,
                                        PROVIDER,
                                        MODEL),
                                right,
                                new GenerationRange(1, 2, 10, 20, 1)),
                        parentRequest(
                                nodeWithIdentity(
                                        "schema",
                                        SESSION_ID,
                                        0,
                                        10,
                                        14,
                                        summary("left", 1, 1, 10, 14),
                                        2,
                                        1,
                                        PROVIDER,
                                        MODEL),
                                right,
                                new GenerationRange(1, 2, 10, 20, 1)),
                        parentRequest(
                                nodeWithIdentity(
                                        "prompt",
                                        SESSION_ID,
                                        0,
                                        10,
                                        14,
                                        summary("left", 1, 1, 10, 14),
                                        1,
                                        2,
                                        PROVIDER,
                                        MODEL),
                                right,
                                new GenerationRange(1, 2, 10, 20, 1)),
                        parentRequest(
                                nodeWithIdentity(
                                        "provider",
                                        SESSION_ID,
                                        0,
                                        10,
                                        14,
                                        summary("left", 1, 1, 10, 14),
                                        1,
                                        1,
                                        "other-provider",
                                        MODEL),
                                right,
                                new GenerationRange(1, 2, 10, 20, 1)),
                        parentRequest(
                                nodeWithIdentity(
                                        "model",
                                        SESSION_ID,
                                        0,
                                        10,
                                        14,
                                        summary("left", 1, 1, 10, 14),
                                        1,
                                        1,
                                        PROVIDER,
                                        "other-model"),
                                right,
                                new GenerationRange(1, 2, 10, 20, 1)));

        invalidRequests.forEach(this::assertRejectedBeforeModel);
    }

    @Test
    void coldSourcePlacesValidatedOldColdFirstThenContiguousPromotions() throws Exception {
        when(model.summarize(any()))
                .thenReturn(result(summary("new cold", 1, 4, 1, 40), SOURCE_SUMMARY_TOKENS));

        generator.generate(validColdRequest());

        ArgumentCaptor<ContextSummaryRequest> request =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model).summarize(request.capture());
        JsonNode source = JSON.readTree(request.getValue().sourceDocument());
        assertThat(source).hasSize(3);
        assertThat(source.get(0).path("confirmedFacts").get(0).path("statement").asString())
                .isEqualTo("old cold");
        assertThat(source.get(1).path("confirmedFacts").get(0).path("statement").asString())
                .isEqualTo("older");
        assertThat(source.get(2).path("confirmedFacts").get(0).path("statement").asString())
                .isEqualTo("newer");
    }

    @ParameterizedTest(name = "rejects untrusted old Cold source: {0}")
    @ValueSource(strings = {"malformed", "unknown-version", "raw-reasoning", "wrong-coverage"})
    void validatesOldColdDocumentAgainstTrustedMetadata(String mutation) {
        String invalidDocument = invalidSourceDocument(mutation, summary("old cold", 1, 2, 1, 20));
        ColdSummary invalidOld = coldSummary(new GenerationRange(1, 2, 1, 20, 0), invalidDocument);

        assertRejectedBeforeModel(
                coldRequest(
                        invalidOld,
                        List.of(
                                node("older", 0, 21, 30, summary("older", 3, 3, 21, 30)),
                                node("newer", 0, 31, 40, summary("newer", 4, 4, 31, 40))),
                        new GenerationRange(1, 4, 1, 40, 0)));
    }

    @ParameterizedTest(name = "rejects untrusted promoted source: {0}")
    @ValueSource(strings = {"malformed", "unknown-version", "raw-reasoning", "wrong-coverage"})
    void validatesEveryPromotedDocumentAgainstTrustedNodeMetadata(String mutation) {
        String invalidDocument = invalidSourceDocument(mutation, summary("older", 3, 3, 21, 30));
        ColdSummary old =
                coldSummary(new GenerationRange(1, 2, 1, 20, 0), summary("old cold", 1, 2, 1, 20));

        assertRejectedBeforeModel(
                coldRequest(
                        old,
                        List.of(
                                node("older", 0, 21, 30, invalidDocument),
                                node("newer", 0, 31, 40, summary("newer", 4, 4, 31, 40))),
                        new GenerationRange(1, 4, 1, 40, 0)));
    }

    @Test
    void rejectsColdGapsOverlapsPartialCoverageWrongLevelsAndIncompatibleIdentities() {
        ColdSummary old =
                coldSummary(new GenerationRange(1, 2, 1, 20, 0), summary("old cold", 1, 2, 1, 20));
        HierarchicalSummaryNode older = node("older", 0, 21, 30, summary("older", 3, 3, 21, 30));
        HierarchicalSummaryNode newer = node("newer", 0, 31, 40, summary("newer", 4, 4, 31, 40));
        List<GenerationRequest> invalidRequests =
                List.of(
                        coldRequest(
                                old,
                                List.of(
                                        node("gap", 0, 22, 30, summary("gap", 3, 3, 22, 30)),
                                        newer),
                                new GenerationRange(1, 4, 1, 40, 0)),
                        coldRequest(
                                old,
                                List.of(
                                        node(
                                                "overlap",
                                                0,
                                                20,
                                                30,
                                                summary("overlap", 3, 3, 20, 30)),
                                        newer),
                                new GenerationRange(1, 4, 1, 40, 0)),
                        coldRequest(
                                coldSummary(
                                        new GenerationRange(1, 3, 1, 30, 0),
                                        summary("old cold", 1, 3, 1, 30)),
                                List.of(
                                        node(
                                                "unaligned-level-one",
                                                1,
                                                31,
                                                40,
                                                summary("unaligned", 4, 5, 31, 40))),
                                new GenerationRange(1, 5, 1, 40, 0)),
                        coldRequest(
                                old, List.of(older, newer), new GenerationRange(1, 4, 1, 41, 0)),
                        coldRequest(
                                old, List.of(older, newer), new GenerationRange(1, 5, 1, 40, 0)),
                        coldRequest(
                                old, List.of(older, newer), new GenerationRange(1, 4, 1, 40, 1)),
                        coldRequest(
                                coldSummaryWithIdentity(
                                        new GenerationRange(1, 2, 1, 20, 0),
                                        summary("old", 1, 2, 1, 20),
                                        1,
                                        1,
                                        "other-provider",
                                        MODEL),
                                List.of(older, newer),
                                new GenerationRange(1, 4, 1, 40, 0)),
                        coldRequest(
                                old,
                                List.of(
                                        nodeWithIdentity(
                                                "schema",
                                                SESSION_ID,
                                                0,
                                                21,
                                                30,
                                                summary("older", 3, 3, 21, 30),
                                                2,
                                                1,
                                                PROVIDER,
                                                MODEL),
                                        newer),
                                new GenerationRange(1, 4, 1, 40, 0)));

        invalidRequests.forEach(this::assertRejectedBeforeModel);
    }

    @Test
    void nodeKeyIsStableAndChangesAcrossRangeSchemaPromptAndModelIdentity() {
        HierarchicalNodeKeyFactory factory = new HierarchicalNodeKeyFactory();
        GenerationRequest baselineRequest = leafRequest();
        SummaryGenerationIdentity baseline =
                new SummaryGenerationIdentity(
                        TENANT_A,
                        SESSION_ID,
                        1,
                        1,
                        new ContextSummaryModelIdentity(PROVIDER, MODEL));
        String baselineKey = factory.create(baselineRequest, baseline);

        assertThat(factory.create(baselineRequest, baseline)).isEqualTo(baselineKey);
        assertThat(
                        List.of(
                                factory.create(
                                        baselineRequest.withRange(
                                                new GenerationRange(1, 1, 10, 10, 0)),
                                        baseline),
                                factory.create(
                                        baselineRequest,
                                        new SummaryGenerationIdentity(
                                                TENANT_A,
                                                SESSION_ID,
                                                2,
                                                1,
                                                baseline.modelIdentity())),
                                factory.create(
                                        baselineRequest,
                                        new SummaryGenerationIdentity(
                                                TENANT_A,
                                                SESSION_ID,
                                                1,
                                                2,
                                                baseline.modelIdentity())),
                                factory.create(
                                        baselineRequest,
                                        new SummaryGenerationIdentity(
                                                TENANT_A,
                                                SESSION_ID,
                                                1,
                                                1,
                                                new ContextSummaryModelIdentity(
                                                        PROVIDER, "other-model")))))
                .doesNotContain(baselineKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void nodeKeyChangesAcrossTenantsForOtherwiseIdenticalGenerationIdentity() {
        HierarchicalNodeKeyFactory factory = new HierarchicalNodeKeyFactory();
        GenerationRange range = new GenerationRange(1, 2, 10, 20, 1);
        ContextSummaryModelIdentity modelIdentity =
                new ContextSummaryModelIdentity(PROVIDER, MODEL);
        SummaryGenerationIdentity tenantA =
                new SummaryGenerationIdentity(TENANT_A, SESSION_ID, 1, 1, modelIdentity);
        SummaryGenerationIdentity tenantB =
                new SummaryGenerationIdentity(TENANT_B, SESSION_ID, 1, 1, modelIdentity);

        assertThat(factory.create(SESSION_ID, SourceKind.PARENT, range, tenantA))
                .isNotEqualTo(factory.create(SESSION_ID, SourceKind.PARENT, range, tenantB));
    }

    @Test
    void nodeKeyRejectsRequestAndIdentityFromDifferentTenants() {
        HierarchicalNodeKeyFactory factory = new HierarchicalNodeKeyFactory();
        GenerationRequest tenantARequest = leafRequest();
        GenerationRequest tenantBRequest =
                new GenerationRequest(
                        TENANT_B,
                        tenantARequest.conversationSessionId(),
                        tenantARequest.range(),
                        tenantARequest.source(),
                        tenantARequest.policySnapshot(),
                        tenantARequest.maxSummaryTokens());
        SummaryGenerationIdentity tenantAIdentity =
                new SummaryGenerationIdentity(
                        TENANT_A,
                        SESSION_ID,
                        1,
                        1,
                        new ContextSummaryModelIdentity(PROVIDER, MODEL));

        assertThatThrownBy(() -> factory.create(tenantBRequest, tenantAIdentity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lengthPrefixesPreventDelimiterCollisionsAcrossIdentityFields() {
        String separator = "\u001f";
        HierarchicalNodeKeyFactory factory = new HierarchicalNodeKeyFactory();
        SummaryGenerationIdentity first =
                new SummaryGenerationIdentity(
                        TENANT_A,
                        SESSION_ID,
                        1,
                        1,
                        new ContextSummaryModelIdentity("provider" + separator + "model", "tail"));
        SummaryGenerationIdentity second =
                new SummaryGenerationIdentity(
                        TENANT_A,
                        SESSION_ID,
                        1,
                        1,
                        new ContextSummaryModelIdentity("provider", "model" + separator + "tail"));

        assertThat(factory.create(leafRequest(), first))
                .isNotEqualTo(factory.create(leafRequest(), second));
    }

    @Test
    void rejectsProviderTokenUsageOrReturnedModelAboveTheFrozenRequestContract() {
        String valid = summary("metric m1 uses unit ms", 1, 2, 10, 20);
        when(model.summarize(any()))
                .thenReturn(
                        result(valid, MAX_SUMMARY_TOKENS + 1),
                        new ContextSummaryResult(valid, 300, PROVIDER, "other-model"));

        assertThatThrownBy(() -> generator.generate(leafRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(HierarchicalSummaryGenerator.FAILURE_CONTEXT_COMPACTION);
        verify(model, times(2)).summarize(any());
    }

    @Test
    void validatesDurableNodeBodyCoverageAndConservativeTokenCount() {
        GenerationRange range = new GenerationRange(1, 2, 10, 20, 1);
        SummaryGenerationIdentity identity =
                generator.generationIdentity(TENANT_A, SESSION_ID, POLICY);
        HierarchicalSummaryNode valid = node("parent", 1, 10, 20, summary("parent", 1, 2, 10, 20));
        HierarchicalSummaryNode wrongCoverage =
                node("wrong-coverage", 1, 10, 20, summary("parent", 1, 1, 10, 20));
        HierarchicalSummaryNode understatedTokens =
                new HierarchicalSummaryNode(
                        "understated",
                        SESSION_ID,
                        1,
                        2,
                        10,
                        20,
                        "left",
                        "right",
                        summary("parent", 1, 2, 10, 20),
                        SOURCE_SUMMARY_TOKENS - 1,
                        1,
                        1,
                        PROVIDER,
                        MODEL);

        generator.validateDurableNode(valid, range, identity, MAX_SUMMARY_TOKENS);
        assertThatThrownBy(
                        () ->
                                generator.validateDurableNode(
                                        wrongCoverage, range, identity, MAX_SUMMARY_TOKENS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                generator.validateDurableNode(
                                        understatedTokens, range, identity, MAX_SUMMARY_TOKENS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesDurableColdBodyCoverageAndFrozenIdentity() {
        GenerationRange range = new GenerationRange(1, 2, 10, 20, 0);
        SummaryGenerationIdentity identity =
                generator.generationIdentity(TENANT_A, SESSION_ID, POLICY);
        HierarchicalColdCandidate valid =
                coldCandidate("cold", range, summary("cold", 1, 2, 10, 20), PROVIDER);
        HierarchicalColdCandidate wrongCoverage =
                coldCandidate("wrong-coverage", range, summary("cold", 1, 1, 10, 20), PROVIDER);
        HierarchicalColdCandidate wrongProvider =
                coldCandidate(
                        "wrong-provider", range, summary("cold", 1, 2, 10, 20), "other-provider");

        generator.validateDurableCold(valid, range, identity, MAX_SUMMARY_TOKENS);
        assertThatThrownBy(
                        () ->
                                generator.validateDurableCold(
                                        wrongCoverage, range, identity, MAX_SUMMARY_TOKENS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                generator.validateDurableCold(
                                        wrongProvider, range, identity, MAX_SUMMARY_TOKENS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejectedBeforeModel(GenerationRequest request) {
        assertThatThrownBy(() -> generator.generate(request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(model, never()).summarize(any());
    }

    private static GenerationRequest leafRequest() {
        ContextMessage assistant = ContextMessage.assistant("answer", "reason privately");
        ContextMessage call =
                ContextMessage.assistantToolCalls(
                        List.of(
                                new ContextToolCall(
                                        "call-1", "metric_query", Map.of("metricId", "m1"))));
        ConversationTurn first =
                new ConversationTurn(
                        1, 10, 12, true, List.of(ContextMessage.user("question"), assistant, call));
        ConversationTurn second =
                new ConversationTurn(
                        2,
                        13,
                        20,
                        true,
                        List.of(
                                ContextMessage.tool("timeout", "call-1", "metric_query"),
                                ContextMessage.assistant("final", null)));
        return GenerationRequest.leaf(
                TENANT_A,
                SESSION_ID,
                new GenerationRange(1, 2, 10, 20, 1),
                List.of(first, second),
                POLICY,
                MAX_SUMMARY_TOKENS);
    }

    private static GenerationRequest validParentRequest() {
        return parentRequest(
                node("left", 0, 10, 14, summary("left fact", 1, 1, 10, 14)),
                node("right", 0, 15, 20, summary("right fact", 2, 2, 15, 20)),
                new GenerationRange(1, 2, 10, 20, 1));
    }

    private static GenerationRequest parentRequest(
            HierarchicalSummaryNode left, HierarchicalSummaryNode right, GenerationRange range) {
        return GenerationRequest.parent(
                TENANT_A, SESSION_ID, range, left, right, POLICY, MAX_SUMMARY_TOKENS);
    }

    private static GenerationRequest validColdRequest() {
        ColdSummary old =
                coldSummary(new GenerationRange(1, 2, 1, 20, 0), summary("old cold", 1, 2, 1, 20));
        return coldRequest(
                old,
                List.of(
                        node("newer", 0, 31, 40, summary("newer", 4, 4, 31, 40)),
                        node("older", 0, 21, 30, summary("older", 3, 3, 21, 30))),
                new GenerationRange(1, 4, 1, 40, 0));
    }

    private static GenerationRequest coldRequest(
            ColdSummary old, List<HierarchicalSummaryNode> promoted, GenerationRange range) {
        return GenerationRequest.cold(
                TENANT_A, SESSION_ID, range, old, promoted, POLICY, MAX_SUMMARY_TOKENS);
    }

    private static ColdSummary coldSummary(GenerationRange range, String document) {
        return coldSummaryWithIdentity(range, document, 1, 1, PROVIDER, MODEL);
    }

    private static ColdSummary coldSummaryWithIdentity(
            GenerationRange range,
            String document,
            int schemaVersion,
            int promptVersion,
            String provider,
            String model) {
        return new ColdSummary(
                SESSION_ID,
                range,
                document,
                SOURCE_SUMMARY_TOKENS,
                schemaVersion,
                promptVersion,
                provider,
                model);
    }

    private static HierarchicalSummaryNode node(
            String id, int level, long startMessage, long endMessage, String summary) {
        return nodeWithIdentity(
                id, SESSION_ID, level, startMessage, endMessage, summary, 1, 1, PROVIDER, MODEL);
    }

    private static HierarchicalSummaryNode nodeWithIdentity(
            String id,
            String sessionId,
            int level,
            long startMessage,
            long endMessage,
            String summary,
            int schemaVersion,
            int promptVersion,
            String provider,
            String model) {
        long coveredTurns = 1L << level;
        return new HierarchicalSummaryNode(
                id,
                sessionId,
                level,
                coveredTurns,
                startMessage,
                endMessage,
                level == 0 ? null : id + "-left",
                level == 0 ? null : id + "-right",
                summary,
                SOURCE_SUMMARY_TOKENS,
                schemaVersion,
                promptVersion,
                provider,
                model);
    }

    private static ContextSummaryResult result(String content, int tokenCount) {
        return new ContextSummaryResult(content, tokenCount, PROVIDER, MODEL);
    }

    private static HierarchicalColdCandidate coldCandidate(
            String candidateId, GenerationRange range, String document, String provider) {
        return new HierarchicalColdCandidate(
                candidateId,
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                document,
                SOURCE_SUMMARY_TOKENS,
                1,
                1,
                provider,
                MODEL);
    }

    private static String invalidSourceDocument(String mutation, String valid) {
        return switch (mutation) {
            case "malformed" -> "{";
            case "unknown-version" -> valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
            case "raw-reasoning" -> valid.replace("\n}", ",\n  \"rawReasoning\": \"hidden\"\n}");
            case "wrong-coverage" -> valid.replaceFirst("\"startTurn\": \\d+", "\"startTurn\": 99");
            default -> throw new IllegalArgumentException("Unknown test mutation");
        };
    }

    private static String summary(
            String fact, long startTurn, long endTurn, long startMessage, long endMessage) {
        return """
                {
                  "schemaVersion": 1,
                  "coverage": {
                    "startTurn": %d,
                    "endTurn": %d,
                    "startMessageSequence": %d,
                    "endMessageSequence": %d
                  },
                  "confirmedFacts": [{
                    "statement": "%s",
                    "sourceRanges": [{"startMessageSequence": %d, "endMessageSequence": %d}]
                  }],
                  "workingHypotheses": [],
                  "decisions": [],
                  "rejectedApproaches": [],
                  "toolFindings": [],
                  "nextSteps": [],
                  "openQuestions": []
                }
                """
                .formatted(
                        startTurn,
                        endTurn,
                        startMessage,
                        endMessage,
                        fact,
                        startMessage,
                        startMessage);
    }
}
