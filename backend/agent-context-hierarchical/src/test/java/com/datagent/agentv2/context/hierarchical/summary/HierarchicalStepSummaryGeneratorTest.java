package com.datagent.agentv2.context.hierarchical.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ContextExecutionFacts;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalStepSummaryDocument;
import com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalStepSummaryGeneratorTest {
    private static final String ID = "model";
    private static final String HASH = "0".repeat(64);
    private static final String DONE = "读取完成";
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private final ContextSummaryModelPort model = mock(ContextSummaryModelPort.class);
    private final ContextSummaryModelIdentity identity = new ContextSummaryModelIdentity(ID, ID);
    private final HierarchicalStepSummaryGenerator generator =
            new HierarchicalStepSummaryGenerator(
                    model, new HierarchicalStepSummaryProtocol(), new ConversationTokenEstimator());

    @Test
    void summaryBatchCountsItsOwnPromptSchemaAndPreviousSummary() {
        var group = group();
        var coverage =
                new StepSummaryCoverage(ContextStepCursor.ORIGIN, group.throughInclusive(), HASH);
        when(model.identity()).thenReturn(identity);
        when(model.summarize(any()))
                .thenAnswer(
                        call -> {
                            ContextSummaryRequest request = call.getArgument(0);
                            assertThat(
                                            request.systemPrompt()
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets.UTF_8)
                                                            .length
                                                    + request.sourceDocument()
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets.UTF_8)
                                                            .length
                                                    + request.outputSchema()
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets.UTF_8)
                                                            .length)
                                    .isLessThanOrEqualTo(request.inputHardTokenBudget());
                            return result(coverage, DONE);
                        });
        var document =
                generator.generate(
                        null,
                        List.of(group),
                        coverage,
                        ContextExecutionFacts.empty(),
                        512,
                        12000,
                        1024,
                        () -> {});
        assertThat(document.entries().getFirst().text()).isEqualTo(DONE);
        verify(model).summarize(any());
    }

    @Test
    void nonShrinkingSummaryStops() {
        var group = group();
        var coverage =
                new StepSummaryCoverage(ContextStepCursor.ORIGIN, group.throughInclusive(), HASH);
        when(model.identity()).thenReturn(identity);
        when(model.summarize(any())).thenReturn(result(coverage, ID.repeat(4000)));
        assertThatThrownBy(
                        () ->
                                generator.generate(
                                        null,
                                        List.of(group),
                                        coverage,
                                        ContextExecutionFacts.empty(),
                                        512,
                                        12000,
                                        1024,
                                        () -> {}))
                .isInstanceOf(IllegalStateException.class);
        verify(model, times(2)).summarize(any());
    }

    @Test
    void quotedSourceAlsoFitsProviderJsonEnvelope() {
        var group =
                new ContextStepGroup(
                        ContextStepCursor.ORIGIN,
                        new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 1),
                        List.of(ContextMessage.assistant("\"".repeat(1500), null)),
                        HASH,
                        true);
        var coverage =
                new StepSummaryCoverage(ContextStepCursor.ORIGIN, group.throughInclusive(), HASH);
        when(model.identity()).thenReturn(identity);
        when(model.summarize(any()))
                .thenAnswer(
                        call -> {
                            ContextSummaryRequest request = call.getArgument(0);
                            var envelope =
                                    java.util.Map.of(
                                            "SUMMARY_SYSTEM",
                                            request.systemPrompt(),
                                            "SOURCE_DOCUMENT",
                                            request.sourceDocument(),
                                            "OUTPUT_SCHEMA",
                                            json.readTree(request.outputSchema()),
                                            "TARGET_RENDERED_TOKENS",
                                            request.targetRenderedTokens(),
                                            "MAX_OUTPUT_TOKENS",
                                            request.maxOutputTokens());
                            // 实际 Codex 适配器将来源再次编码为 JSON 字符串，另有提示前缀和固定指令。
                            assertThat(json.writeValueAsBytes(envelope).length + 700)
                                    .isLessThanOrEqualTo(request.inputHardTokenBudget());
                            return result(coverage, DONE);
                        });
        generator.generate(
                null,
                List.of(group),
                coverage,
                ContextExecutionFacts.empty(),
                512,
                8000,
                1024,
                () -> {});
    }

    private ContextSummaryResult result(StepSummaryCoverage coverage, String content) {
        var doc =
                new HierarchicalStepSummaryDocument(
                        3,
                        coverage,
                        List.of(
                                new HierarchicalStepSummaryDocument.Entry(
                                        HierarchicalStepSummaryDocument.Kind.FACT,
                                        content,
                                        List.of(coverage.throughInclusive()))));
        return new ContextSummaryResult(json.writeValueAsString(doc), 0, ID, ID);
    }

    private ContextStepGroup group() {
        return new ContextStepGroup(
                ContextStepCursor.ORIGIN,
                new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, 1),
                List.of(ContextMessage.assistant(ID.repeat(1000), null)),
                HASH,
                true);
    }
}
