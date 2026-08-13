package com.datagent.agentv2.context.hierarchical.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class HierarchicalSummaryGeneratorTest {

    private static final String SESSION_ID = "session-1";
    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "summary-model";
    private static final int MAX_SUMMARY_TOKENS = 1_024;
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
        lenient().when(estimator.estimateSummary(any())).thenReturn(300);
        generator =
                new HierarchicalSummaryGenerator(
                        model,
                        new ContextSummaryDocumentValidator(estimator),
                        new HierarchicalNodeKeyFactory());
    }

    @Test
    void retriesOneInvalidDocumentWithCorrectionAndReturnsSecondValidDocument() {
        when(model.summarize(any()))
                .thenReturn(result("{\"confirmedFacts\":{}}", 20), result(validSummary(), 300));

        GeneratedSummary generated = generator.generate(leafRequest());

        assertThat(generated.document()).isEqualTo(validSummary());
        assertThat(generated.estimatedTokenCount()).isEqualTo(300);
        ArgumentCaptor<ContextSummaryRequest> requests =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model, times(2)).summarize(requests.capture());
        assertThat(requests.getAllValues().get(1).systemPrompt())
                .contains("上一次输出未通过校验")
                .doesNotContain("confirmedFacts");
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
        when(model.summarize(any())).thenReturn(result(validSummary(), 300));

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
    void parentSourceContainsOnlyLeftThenRightSummary() throws Exception {
        when(model.summarize(any())).thenReturn(result(validSummary(), 300));
        HierarchicalSummaryNode left = node("left", 1, 10, 10, summaryWithFact("left fact"));
        HierarchicalSummaryNode right = node("right", 1, 20, 20, summaryWithFact("right fact"));

        assertThat(left.conversationSessionId()).isEqualTo(SESSION_ID);
        assertThat(right.conversationSessionId()).isEqualTo(SESSION_ID);
        assertThat(left.level()).isEqualTo(right.level());
        assertThat(left.startConversationMessageSequence()).isEqualTo(10);
        assertThat(right.endConversationMessageSequence()).isEqualTo(20);

        generator.generate(
                GenerationRequest.parent(
                        SESSION_ID,
                        new GenerationRange(1, 2, 10, 20, 1),
                        left,
                        right,
                        POLICY,
                        MAX_SUMMARY_TOKENS));

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

    @Test
    void coldSourcePlacesOldColdFirstThenPromotedNodesChronologically() throws Exception {
        when(model.summarize(any())).thenReturn(result(validSummary(), 300));
        HierarchicalSummaryNode older = node("older", 1, 10, 10, summaryWithFact("older"));
        HierarchicalSummaryNode newer = node("newer", 1, 20, 20, summaryWithFact("newer"));

        generator.generate(
                GenerationRequest.cold(
                        SESSION_ID,
                        new GenerationRange(1, 2, 10, 20, 0),
                        summaryWithFact("old cold"),
                        List.of(newer, older),
                        POLICY,
                        MAX_SUMMARY_TOKENS));

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

    @Test
    void nodeKeyIsStableAndChangesAcrossRangeSchemaPromptAndModelIdentity() {
        HierarchicalNodeKeyFactory factory = new HierarchicalNodeKeyFactory();
        GenerationRequest baselineRequest = leafRequest();
        SummaryGenerationIdentity baseline =
                new SummaryGenerationIdentity(
                        SESSION_ID, 1, 1, new ContextSummaryModelIdentity(PROVIDER, MODEL));
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
                                                SESSION_ID, 2, 1, baseline.modelIdentity())),
                                factory.create(
                                        baselineRequest,
                                        new SummaryGenerationIdentity(
                                                SESSION_ID, 1, 2, baseline.modelIdentity())),
                                factory.create(
                                        baselineRequest,
                                        new SummaryGenerationIdentity(
                                                SESSION_ID,
                                                1,
                                                1,
                                                new ContextSummaryModelIdentity(
                                                        PROVIDER, "other-model")))))
                .doesNotContain(baselineKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsProviderTokenUsageOrReturnedModelAboveTheFrozenRequestContract() {
        when(model.summarize(any()))
                .thenReturn(
                        result(validSummary(), MAX_SUMMARY_TOKENS + 1),
                        new ContextSummaryResult(validSummary(), 300, PROVIDER, "other-model"));

        assertThatThrownBy(() -> generator.generate(leafRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(HierarchicalSummaryGenerator.FAILURE_CONTEXT_COMPACTION);
        verify(model, times(2)).summarize(any());
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
                SESSION_ID,
                new GenerationRange(1, 2, 10, 20, 1),
                List.of(first, second),
                POLICY,
                MAX_SUMMARY_TOKENS);
    }

    private static HierarchicalSummaryNode node(
            String id, long coveredTurns, long startMessage, long endMessage, String summary) {
        int level = coveredTurns == 1 ? 0 : 1;
        return new HierarchicalSummaryNode(
                id,
                SESSION_ID,
                level,
                coveredTurns,
                startMessage,
                endMessage,
                level == 0 ? null : id + "-left",
                level == 0 ? null : id + "-right",
                summary,
                100,
                1,
                1,
                PROVIDER,
                MODEL);
    }

    private static ContextSummaryResult result(String content, int tokenCount) {
        return new ContextSummaryResult(content, tokenCount, PROVIDER, MODEL);
    }

    private static String summaryWithFact(String fact) {
        return validSummary().replace("metric m1 uses unit ms", fact);
    }

    private static String validSummary() {
        return """
                {
                  "schemaVersion": 1,
                  "coverage": {
                    "startTurn": 1,
                    "endTurn": 2,
                    "startMessageSequence": 10,
                    "endMessageSequence": 20
                  },
                  "confirmedFacts": [{
                    "statement": "metric m1 uses unit ms",
                    "sourceRanges": [{"startMessageSequence": 10, "endMessageSequence": 10}]
                  }],
                  "workingHypotheses": [],
                  "decisions": [],
                  "rejectedApproaches": [],
                  "toolFindings": [],
                  "nextSteps": [],
                  "openQuestions": []
                }
                """;
    }
}
