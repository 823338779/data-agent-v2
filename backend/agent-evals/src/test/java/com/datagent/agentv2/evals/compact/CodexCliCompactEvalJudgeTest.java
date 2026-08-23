package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodexCliCompactEvalJudgeTest {

    private final CodexCliCompactEvalJudge judge =
            new CodexCliCompactEvalJudge(
                    "codex", "test-model", "medium", Duration.ofSeconds(1), new ObjectMapper());

    @Test
    void parsesStructuredJudgmentAndAppliesPassThreshold() throws Exception {
        CompactEvalJudge.Judgment passing =
                judge.parseJudgment("{\"correct\":true,\"score\":0.75,\"reason\":\"核心答案正确\"}");
        CompactEvalJudge.Judgment failing =
                judge.parseJudgment("{\"correct\":false,\"score\":0.5,\"reason\":\"遗漏重要信息\"}");

        assertEquals(0.75, passing.score());
        assertTrue(passing.correct());
        assertFalse(failing.correct());
        assertEquals("medium", judge.effort());
        assertFalse(judge.promptVersion().isBlank());
        assertFalse(judge.rubricVersion().isBlank());
    }

    @Test
    void rejectsOutOfRangeOrIncompleteJudgment() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        judge.parseJudgment(
                                "{\"correct\":true,\"score\":1.1,\"reason\":\"invalid\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        judge.parseJudgment(
                                "{\"correct\":true,\"score\":0.8,\"reason\":\"unsupported\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> judge.parseJudgment("{\"correct\":true,\"score\":0.75,\"reason\":\"\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> judge.parseJudgment("{\"correct\":true,\"score\":0.5,\"reason\":\"冲突\"}"));
    }

    @Test
    void selectsLongMemEvalRubricFromScenarioMetadata() {
        String temporalPolicy =
                CodexCliCompactEvalJudge.evaluationPolicy(metadata("temporal-reasoning", false));
        String abstentionPolicy =
                CodexCliCompactEvalJudge.evaluationPolicy(metadata("multi-session", true));

        assertTrue(temporalPolicy.contains("正负一"));
        assertTrue(abstentionPolicy.contains("不可回答"));
    }

    private static CompactEvalCorpus.ScenarioMetadata metadata(
            String questionType, boolean abstention) {
        return new CompactEvalCorpus.ScenarioMetadata(
                "LongMemEval",
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
