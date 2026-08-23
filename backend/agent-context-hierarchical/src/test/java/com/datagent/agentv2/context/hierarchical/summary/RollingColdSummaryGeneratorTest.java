package com.datagent.agentv2.context.hierarchical.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.common.summary.EventContextSummaryRenderer;
import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator.GenerationBatch;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RollingColdSummaryGeneratorTest {
    @Mock ContextSummaryModelPort model;

    @Test
    void generatesAuditableV2AndUsesCompactRenderedBudgetWithoutSourceMode() {
        EventSummaryCoverage coverage = new EventSummaryCoverage(1, 1, 10, 11);
        when(model.summarize(any()))
                .thenReturn(new ContextSummaryResult(validJson(), 200, "provider", "model"));
        RollingColdSummaryGenerator generator =
                new RollingColdSummaryGenerator(
                        model,
                        new EventContextSummaryValidator(),
                        new EventContextSummaryRenderer(),
                        new ConversationTokenEstimator(),
                        new HierarchicalColdKeyFactory());
        ConversationTurn turn =
                new ConversationTurn(
                        1,
                        10,
                        11,
                        true,
                        List.of(ContextMessage.user("问题"), ContextMessage.assistant("答案", null)));

        var generated =
                generator.generate(
                        new GenerationRequest(
                                "tenant",
                                "session",
                                null,
                                List.of(new GenerationBatch(coverage, List.of(turn))),
                                coverage,
                                5_000,
                                1_024,
                                32_768,
                                new ContextSummaryModelIdentity("provider", "model")));

        assertThat(generated.document()).contains("\"schemaVersion\":2");
        assertThat(generated.renderedText())
                .contains("[事件] 会话压缩｜已完成")
                .doesNotContain("sourceRanges");
        ArgumentCaptor<ContextSummaryRequest> request =
                ArgumentCaptor.forClass(ContextSummaryRequest.class);
        verify(model).summarize(request.capture());
        assertThat(request.getValue().systemPrompt())
                .contains("context summary schema v2")
                .contains("TARGET_RENDERED_TOKENS=5000")
                .doesNotContain("SOURCE_MODE=")
                .doesNotContain("最近 10");
        assertThat(request.getValue().sourceDocument()).contains("[新增原始对话]");
    }

    @Test
    void splitsAPlannedBatchAgainWhenPromptSchemaAndSourceExceedTheHardInputBudget() {
        EventSummaryCoverage coverage = new EventSummaryCoverage(1, 2, 10, 13);
        when(model.summarize(any()))
                .thenReturn(
                        new ContextSummaryResult(validJson(1, 10, 11), 200, "provider", "model"),
                        new ContextSummaryResult(validJson(2, 10, 13), 200, "provider", "model"));
        RollingColdSummaryGenerator generator = generator();
        ConversationTurn first = turn(1, 10, 11, "甲".repeat(5_000));
        ConversationTurn second = turn(2, 12, 13, "乙".repeat(5_000));

        var generated =
                generator.generate(
                        new GenerationRequest(
                                "tenant",
                                "session",
                                null,
                                List.of(new GenerationBatch(coverage, List.of(first, second))),
                                coverage,
                                5_000,
                                1_024,
                                25_000,
                                new ContextSummaryModelIdentity("provider", "model")));

        assertThat(generated.coverage()).isEqualTo(coverage);
        verify(model, times(2)).summarize(any());
    }

    private RollingColdSummaryGenerator generator() {
        return new RollingColdSummaryGenerator(
                model,
                new EventContextSummaryValidator(),
                new EventContextSummaryRenderer(),
                new ConversationTokenEstimator(),
                new HierarchicalColdKeyFactory());
    }

    private static ConversationTurn turn(
            long ordinal, long startMessage, long endMessage, String content) {
        return new ConversationTurn(
                ordinal,
                startMessage,
                endMessage,
                true,
                List.of(ContextMessage.user(content), ContextMessage.assistant("答案", null)));
    }

    private static String validJson() {
        return validJson(1, 10, 11);
    }

    private static String validJson(long endTurn, long startMessage, long endMessage) {
        return """
                {
                  "schemaVersion": 2,
                  "coverage": {"startTurnSequence":1,"endTurnSequence":%d,"startMessageSequence":%d,"endMessageSequence":%d},
                  "events": [{
                    "title":{"text":"会话压缩","sourceRanges":[{"startMessageSequence":%d,"endMessageSequence":%d}]},
                    "status":{"value":"COMPLETED","sourceRanges":[{"startMessageSequence":%d,"endMessageSequence":%d}]},
                    "confirmedFacts":[{"statement":"用户问题已回答","sourceRanges":[{"startMessageSequence":%d,"endMessageSequence":%d}]}],
                    "userPreferences":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"openQuestions":[],"nextSteps":[]
                  }]
                }
                """
                .formatted(
                        endTurn,
                        startMessage,
                        endMessage,
                        startMessage,
                        endMessage,
                        startMessage,
                        endMessage,
                        startMessage,
                        endMessage);
    }
}
