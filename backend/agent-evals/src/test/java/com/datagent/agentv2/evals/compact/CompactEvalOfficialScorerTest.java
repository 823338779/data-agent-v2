package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompactEvalOfficialScorerTest {

    private final CompactEvalOfficialScorer scorer = new CompactEvalOfficialScorer();

    @Test
    void usesLongMemEvalTaskAwareBinaryJudgment() {
        CompactEvalReport.OfficialScore score =
                scorer.score(
                        metadata("LongMemEval", "temporal-reasoning", false),
                        "18 days",
                        "19 days",
                        new CompactEvalJudge.Judgment(0.75, true, "允许相差一天"));

        assertEquals("longmemeval-llm-accuracy-compatible/v1", score.method());
        assertEquals("completed", score.status());
        assertEquals(1.0, score.score());
        assertTrue(score.passed());
    }

    @Test
    void computesCLongEvalChineseQaF1InsteadOfUsingJudgeScore() {
        CompactEvalReport.OfficialScore score =
                scorer.score(
                        metadata("CLongEval", "long-conversation-memory", false),
                        "《小王子》",
                        "我推荐的书是《小王子》。",
                        new CompactEvalJudge.Judgment(0.25, false, "unused"));

        assertEquals("clongeval-lcvmem-qa-f1-zh-compatible/v1", score.method());
        assertEquals("completed", score.status());
        assertTrue(score.score() > 0.0);
        assertTrue(score.score() < 1.0);
        assertNull(score.passed());
    }

    @Test
    void computesNeedleBenchMultiReasoningKeywordRecall() {
        CompactEvalReport.OfficialScore matched =
                scorer.score(
                        metadata("NeedleBench", "multi-needle-reasoning", false),
                        "希拉里·罗德姆·克林顿",
                        "答案是希拉里·罗德姆·克林顿。",
                        new CompactEvalJudge.Judgment(0.0, false, "unused"));
        CompactEvalReport.OfficialScore missed =
                scorer.score(
                        metadata("NeedleBench", "multi-needle-reasoning", false),
                        "希拉里·罗德姆·克林顿",
                        "答案是比尔·克林顿。",
                        new CompactEvalJudge.Judgment(1.0, true, "unused"));

        assertEquals("needlebench-multi-keyword-recall/v1", matched.method());
        assertEquals(1.0, matched.score());
        assertEquals(0.0, missed.score());
        assertNull(matched.passed());
    }

    @Test
    void marksNativeCorpusAsNotApplicable() {
        CompactEvalReport.OfficialScore score =
                scorer.score(
                        metadata("custom", null, false),
                        "42",
                        "42",
                        new CompactEvalJudge.Judgment(1.0, true, "correct"));

        assertEquals("not-applicable", score.status());
        assertNull(score.score());
        assertFalse(scorer.supports(metadata("custom", null, false)));
    }

    private static CompactEvalCorpus.ScenarioMetadata metadata(
            String dataset, String questionType, boolean abstention) {
        return new CompactEvalCorpus.ScenarioMetadata(
                dataset,
                "test",
                questionType,
                null,
                null,
                abstention,
                null,
                null,
                null,
                1,
                Map.of(),
                List.of());
    }
}
