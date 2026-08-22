package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactEvalRunnerTest {

    private static final String EXPECTED_ANSWER = "上海";

    @Test
    void replaysHistoryBeforeProbeAndKeepsRawAnswers() throws Exception {
        CompactEvalCorpus corpus =
                new CompactEvalCorpus(
                        "test",
                        List.of(
                                new CompactEvalCorpus.Scenario(
                                        "scenario-1",
                                        "runner-test",
                                        List.of("旧决定是北京", "最新决定改为上海"),
                                        List.of(
                                                new CompactEvalCorpus.Probe(
                                                        "最终决定是什么？", EXPECTED_ANSWER)))));
        FakeGateway gateway = new FakeGateway();

        CompactEvalRunArtifact artifact =
                new CompactEvalRunner()
                        .run("test-mechanism", corpus, gateway, Duration.ofSeconds(1), 0);

        assertEquals(List.of("旧决定是北京", "最新决定改为上海", "最终决定是什么？"), gateway.inputs);
        assertTrue(artifact.scenarios().getFirst().completed());
        assertTrue(artifact.scenarios().getFirst().turns().getLast().referenceMatched());
    }

    @Test
    void preservesCompletedTurnsWhenGatewayFails() throws Exception {
        CompactEvalCorpus corpus =
                new CompactEvalCorpus(
                        "test",
                        List.of(
                                new CompactEvalCorpus.Scenario(
                                        "scenario-1",
                                        "runner-test",
                                        List.of("first", "second"),
                                        List.of(
                                                new CompactEvalCorpus.Probe(
                                                        "probe", EXPECTED_ANSWER)))));
        CompactEvalGateway gateway = new FailingGateway();

        CompactEvalRunArtifact artifact =
                new CompactEvalRunner()
                        .run("test-mechanism", corpus, gateway, Duration.ofSeconds(1), 0);

        CompactEvalRunArtifact.ScenarioResult result = artifact.scenarios().getFirst();
        assertFalse(result.completed());
        assertEquals(1, result.turns().size());
        assertTrue(result.failure().contains("simulated failure"));
    }

    /** 用确定性网关隔离测试回放顺序和结果投影。 */
    private static final class FakeGateway implements CompactEvalGateway {
        private final List<String> inputs = new ArrayList<>();

        @Override
        public String createSession(String title) {
            return "session-1";
        }

        @Override
        public TurnResult submitAndAwait(String sessionId, String content, Duration timeout) {
            inputs.add(content);
            String answer = content.endsWith("？") ? "最终决定是上海。" : "已读取";
            return new TurnResult(
                    "execution-" + inputs.size(), "done", answer, inputs.size() * 2, 0, 0, 0, 1L);
        }
    }

    /** 第二轮抛错，用于验证长跑的部分产物不会因单轮异常消失。 */
    private static final class FailingGateway implements CompactEvalGateway {
        private int submissions;

        @Override
        public String createSession(String title) {
            return "session-1";
        }

        @Override
        public TurnResult submitAndAwait(String sessionId, String content, Duration timeout) {
            submissions++;
            if (submissions == 2) {
                throw new IllegalStateException("simulated failure");
            }
            return new TurnResult("execution-1", "done", "ok", 2, 0, 0, 0, 1L);
        }
    }
}
