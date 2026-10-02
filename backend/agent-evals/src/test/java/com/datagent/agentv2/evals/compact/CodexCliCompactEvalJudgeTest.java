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
    void interruptionAlwaysTerminatesTheStartedProcess() throws Exception {
        Process child =
                new ProcessBuilder(
                                java.nio.file.Path.of(
                                                System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SilentChild.class.getName())
                        .start();
        var started = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interruptedJudge =
                new CodexCliCompactEvalJudge(
                        "unused",
                        "unit",
                        "medium",
                        Duration.ofSeconds(30),
                        new ObjectMapper(),
                        (command, log) -> {
                            started.countDown();
                            return child;
                        });
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        interruptedJudge.judge("question", "answer", "candidate");
                                    } catch (Exception exception) {
                                        failure.set(exception);
                                    }
                                });
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertFalse(child.isAlive(), "cancelled judge must not leave a child process");
        } finally {
            child.destroyForcibly();
            worker.interrupt();
        }
    }

    @Test
    void timeoutAlsoCoversWritingToAChildThatNeverReadsStdin() throws Exception {
        Process child =
                new ProcessBuilder(
                                java.nio.file.Path.of(
                                                System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SilentChild.class.getName())
                        .start();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var blockedJudge =
                new CodexCliCompactEvalJudge(
                        "unused",
                        "unit",
                        "medium",
                        Duration.ofMillis(100),
                        new ObjectMapper(),
                        (command, log) -> child);
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        blockedJudge.judge(
                                                "q".repeat(2_000_000), "answer", "candidate");
                                    } catch (Exception exception) {
                                        failure.set(exception);
                                    }
                                });
        try {
            worker.join(3000);
            assertFalse(worker.isAlive(), "stdin write must share the judge deadline");
            assertTrue(failure.get() instanceof IllegalStateException);
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
            worker.interrupt();
            worker.join(2000);
        }
    }

    public static class SilentChild {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30000);
        }
    }

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
