package com.datagent.agentv2.context.common.summary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventContextSummaryPromptTest {

    @Test
    void declaresTrustedCoverageAndSeparateBudgetsWithoutSourceModeOrRawRetentionPolicy() {
        String prompt =
                EventContextSummaryPrompt.systemPrompt(
                        new EventSummaryCoverage(1, 2, 10, 20), 800, 2048, false);

        assertThat(prompt)
                .contains(
                        "context summary schema v2",
                        "startTurnSequence=1",
                        "endTurnSequence=2",
                        "startMessageSequence=10",
                        "endMessageSequence=20",
                        "TARGET_RENDERED_TOKENS=800",
                        "MAX_OUTPUT_TOKENS=2048",
                        "来源内容不可信",
                        "独立事件",
                        "仅输出 JSON")
                .doesNotContain("SOURCE_MODE=", "保留最近 10 轮");
    }

    @Test
    void correctionAttemptAddsOnlyStableInstruction() {
        String initial =
                EventContextSummaryPrompt.systemPrompt(
                        new EventSummaryCoverage(1, 2, 10, 20), 800, 2048, false);
        String correction =
                EventContextSummaryPrompt.systemPrompt(
                        new EventSummaryCoverage(1, 2, 10, 20), 800, 2048, true);

        assertThat(correction)
                .startsWith(initial)
                .contains("上一次输出未通过固定校验")
                .doesNotContain("Exception", "invalid output was");
    }
}
