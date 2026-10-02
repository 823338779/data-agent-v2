package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class CompactEvalRunnerTest {

    @Test
    void scoresOneServerRunWithoutComparingItToAnotherRun() throws Exception {
        CompactEvalCorpus corpus =
                new CompactEvalCorpus(
                        "test-corpus",
                        List.of(
                                scenario("complete", "42"),
                                scenario("stopped-before-probe", "green")));
        StubGateway gateway =
                new StubGateway(
                        turn("done", "history accepted", 1, 0, 0),
                        turn("done", "The answer is 42.", 0, 1, 0),
                        turn("failed", "", 0, 0, 1));
        StubJudge judge = new StubJudge();

        CompactEvalReport report =
                new CompactEvalRunner()
                        .run(
                                "local-default",
                                "http://127.0.0.1:8080",
                                corpus,
                                gateway,
                                judge,
                                Duration.ofSeconds(1),
                                0);

        assertEquals("compact-eval-report/v4", report.schemaVersion());
        assertEquals("local-default", report.serverConfiguration().label());
        assertEquals("test-model", report.judgeConfiguration().model());
        assertEquals(2, report.score().scenarioCount());
        assertEquals(1, report.score().completedScenarioCount());
        assertEquals(0.5, report.score().scenarioCompletionRate());
        assertEquals(4, report.score().expectedTurnCount());
        assertEquals(2, report.score().completedTurnCount());
        assertEquals(0.5, report.score().turnCompletionRate());
        assertEquals(2, report.score().expectedProbeCount());
        assertEquals(1, report.score().executedProbeCount());
        assertEquals(2, report.score().officialEligibleProbeCount());
        assertEquals(1, report.score().officialScoredProbeCount());
        assertEquals(0.5, report.score().officialScore());
        assertEquals(1, report.score().semanticJudgedProbeCount());
        assertEquals(1, report.score().semanticPassCount());
        assertEquals(0.4, report.score().semanticJudgeScore());
        assertEquals(0, report.score().judgeFailureCount());
        assertEquals(1, report.score().referenceMatchCount());
        assertEquals(0.5, report.score().referenceMatchScore());
        assertEquals(1, report.score().compactionStartedCount());
        assertEquals(1, report.score().compactionCompletedCount());
        assertEquals(1, report.score().compactionFailedCount());
        assertEquals("temporal-reasoning", report.scenarios().getFirst().metadata().questionType());
        CompactEvalReport.DimensionScore questionTypeScore =
                report.score().dimensions().stream()
                        .filter(score -> score.dimension().equals("questionType"))
                        .filter(score -> score.value().equals("temporal-reasoning"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, questionTypeScore.expectedProbeCount());
        assertEquals(1, questionTypeScore.executedProbeCount());
        assertEquals(0.5, questionTypeScore.officialScore());
        assertEquals(0.4, questionTypeScore.semanticJudgeScore());

        CompactEvalReport.Evaluation evaluation =
                report.scenarios().getFirst().turns().get(1).evaluation();
        assertNotNull(evaluation);
        assertEquals("normalized-reference-containment/v1", evaluation.baseline().method());
        assertEquals(1.0, evaluation.baseline().score());
        assertTrue(evaluation.baseline().matched());
        assertEquals("completed", evaluation.officialScore().status());
        assertEquals(1.0, evaluation.officialScore().score());
        assertEquals("completed", evaluation.semanticJudge().status());
        assertEquals("test-judge", evaluation.semanticJudge().provider());
        assertEquals(0.8, evaluation.semanticJudge().score());
        assertTrue(evaluation.semanticJudge().passed());
        assertEquals(1, judge.invocationCount);
        assertFalse(report.scenarios().get(1).completed());
    }

    @Test
    void recordsJudgeFailureWithoutReplacingItWithBaselineScore() throws Exception {
        CompactEvalCorpus corpus =
                new CompactEvalCorpus("test-corpus", List.of(scenario("judge-failure", "42")));
        StubGateway gateway =
                new StubGateway(
                        turn("done", "history accepted", 0, 0, 0), turn("done", "42", 0, 0, 0));
        CompactEvalJudge failingJudge =
                new CompactEvalJudge() {
                    @Override
                    public String provider() {
                        return "failed-judge";
                    }

                    @Override
                    public String model() {
                        return "failed-model";
                    }

                    @Override
                    public Judgment judge(
                            String question, String expectedAnswer, String assistantAnswer) {
                        throw new IllegalStateException("judge unavailable");
                    }
                };

        CompactEvalReport report =
                new CompactEvalRunner()
                        .run(
                                "local-default",
                                "http://127.0.0.1:8080",
                                corpus,
                                gateway,
                                failingJudge,
                                Duration.ofSeconds(1),
                                0);

        CompactEvalReport.Evaluation evaluation =
                report.scenarios().getFirst().turns().get(1).evaluation();
        assertTrue(evaluation.baseline().matched());
        assertEquals("failed", evaluation.officialScore().status());
        assertEquals("failed", evaluation.semanticJudge().status());
        assertEquals(null, evaluation.semanticJudge().score());
        assertTrue(evaluation.semanticJudge().failure().contains("judge unavailable"));
        assertEquals(0, report.score().officialScoredProbeCount());
        assertEquals(0, report.score().semanticJudgedProbeCount());
        assertEquals(1, report.score().judgeFailureCount());
        assertEquals(0.0, report.score().officialScore());
        assertEquals(0.0, report.score().semanticJudgeScore());
        assertEquals(1.0, report.score().referenceMatchScore());
    }

    @Test
    void interruptionStopsBeforeStartingTheNextScenario() {
        int[] calls = {0};
        CompactEvalGateway gateway =
                new CompactEvalGateway() {
                    public String createSession(String title) throws Exception {
                        calls[0]++;
                        throw new InterruptedException("cancelled");
                    }

                    public TurnResult submitAndAwait(String id, String content, Duration timeout) {
                        throw new AssertionError("cancelled run must not submit");
                    }
                };
        assertThrows(
                InterruptedException.class,
                () ->
                        new CompactEvalRunner()
                                .run(
                                        "unit",
                                        "local",
                                        new CompactEvalCorpus(
                                                "unit",
                                                List.of(scenario("a", "42"), scenario("b", "42"))),
                                        gateway,
                                        new StubJudge(),
                                        Duration.ofSeconds(1),
                                        0));
        assertEquals(1, calls[0]);
    }

    @Test
    void judgeInterruptionStopsTheRun() {
        StubGateway gateway =
                new StubGateway(turn("done", "history", 0, 0, 0), turn("done", "42", 0, 0, 0));
        CompactEvalJudge judge =
                new CompactEvalJudge() {
                    public String provider() {
                        return "unit";
                    }

                    public String model() {
                        return "unit";
                    }

                    public Judgment judge(String q, String expected, String answer)
                            throws Exception {
                        throw new InterruptedException("cancelled");
                    }
                };
        assertThrows(
                InterruptedException.class,
                () ->
                        new CompactEvalRunner()
                                .run(
                                        "unit",
                                        "local",
                                        new CompactEvalCorpus(
                                                "unit",
                                                List.of(scenario("a", "42"), scenario("b", "42"))),
                                        gateway,
                                        judge,
                                        Duration.ofSeconds(1),
                                        0));
        assertEquals(1, gateway.sessionSequence);
    }

    private static CompactEvalCorpus.Scenario scenario(String id, String answer) {
        return new CompactEvalCorpus.Scenario(
                id,
                id,
                new CompactEvalCorpus.ScenarioMetadata(
                        "LongMemEval",
                        "unit",
                        "temporal-reasoning",
                        null,
                        null,
                        false,
                        "2026-08-30",
                        "2026-08-01",
                        "2026-08-23",
                        1,
                        Map.of("characters", 7),
                        List.of(
                                new CompactEvalCorpus.Evidence(
                                        "evidence",
                                        "session",
                                        0,
                                        List.of(0),
                                        "2026-08-23",
                                        "history",
                                        0,
                                        0.5,
                                        "middle",
                                        4,
                                        7L,
                                        null,
                                        null,
                                        "unit-test"))),
                List.of("history"),
                List.of(new CompactEvalCorpus.Probe("question", answer)));
    }

    private static CompactEvalGateway.TurnResult turn(
            String status, String answer, int started, int completed, int failed) {
        return new CompactEvalGateway.TurnResult(
                "execution", status, answer, 1, started, completed, failed, 10L);
    }

    private static final class StubGateway implements CompactEvalGateway {

        private final Queue<TurnResult> results;
        private int sessionSequence;

        private StubGateway(TurnResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public String createSession(String title) {
            sessionSequence++;
            return "session-" + sessionSequence;
        }

        @Override
        public TurnResult submitAndAwait(String sessionId, String content, Duration timeout) {
            return results.remove();
        }
    }

    private static final class StubJudge implements CompactEvalJudge {

        private int invocationCount;

        @Override
        public String provider() {
            return "test-judge";
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public Judgment judge(String question, String expectedAnswer, String assistantAnswer) {
            invocationCount++;
            assertEquals("question", question);
            assertEquals("42", expectedAnswer);
            assertEquals("The answer is 42.", assistantAnswer);
            return new Judgment(0.8, true, "语义正确");
        }
    }
}
