package com.datagent.agentv2.evals.compact;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 在单个已启动机制上顺序执行完整语料，避免共享队列把 A/B 任务调度到错误实例。 */
public final class CompactEvalRunner {

    private static final String ARTIFACT_SCHEMA_VERSION = "compact-eval-run/v1";
    private static final String HISTORY_KIND = "history";
    private static final String PROBE_KIND = "probe";
    private static final String DONE_STATUS = "done";

    /** 回放指定数量的场景；maxScenarios 小于一时表示不限制。 */
    public CompactEvalRunArtifact run(
            String mechanism,
            CompactEvalCorpus corpus,
            CompactEvalGateway gateway,
            Duration turnTimeout,
            int maxScenarios)
            throws Exception {
        Instant startedAt = Instant.now();
        int scenarioLimit =
                maxScenarios < 1
                        ? corpus.scenarios().size()
                        : Math.min(maxScenarios, corpus.scenarios().size());
        List<CompactEvalRunArtifact.ScenarioResult> scenarios = new ArrayList<>();

        // 每个场景独占一条产品会话，场景失败后继续保留已经完成的原始轮次。
        for (int scenarioIndex = 0; scenarioIndex < scenarioLimit; scenarioIndex++) {
            CompactEvalCorpus.Scenario scenario = corpus.scenarios().get(scenarioIndex);
            String sessionId = null;
            List<CompactEvalRunArtifact.TurnResult> turns = new ArrayList<>();
            boolean completed = false;
            String failure = null;
            try {
                sessionId = gateway.createSession(scenario.title());
                completed = replayHistory(scenario, sessionId, gateway, turnTimeout, turns);
                if (completed) {
                    completed = replayProbes(scenario, sessionId, gateway, turnTimeout, turns);
                }
            } catch (Exception scenarioFailure) {
                failure =
                        scenarioFailure.getClass().getSimpleName()
                                + ": "
                                + scenarioFailure.getMessage();
            }
            scenarios.add(
                    new CompactEvalRunArtifact.ScenarioResult(
                            scenario.id(), sessionId, completed, failure, List.copyOf(turns)));
        }
        return new CompactEvalRunArtifact(
                ARTIFACT_SCHEMA_VERSION,
                mechanism,
                corpus.source(),
                startedAt.toString(),
                Duration.between(startedAt, Instant.now()).toMillis(),
                List.copyOf(scenarios));
    }

    /** 历史轮只负责推动真实上下文增长；任一执行失败即停止当前场景。 */
    private boolean replayHistory(
            CompactEvalCorpus.Scenario scenario,
            String sessionId,
            CompactEvalGateway gateway,
            Duration timeout,
            List<CompactEvalRunArtifact.TurnResult> turns)
            throws Exception {
        for (String history : scenario.history()) {
            CompactEvalGateway.TurnResult result =
                    gateway.submitAndAwait(sessionId, history, timeout);
            turns.add(toArtifactTurn(turns.size(), HISTORY_KIND, history, null, result));
            if (!DONE_STATUS.equals(result.status())) {
                return false;
            }
        }
        return true;
    }

    /** 探针轮保留参考答案和宽松字符串命中结果，同时保留原回答供后续人工或模型裁判。 */
    private boolean replayProbes(
            CompactEvalCorpus.Scenario scenario,
            String sessionId,
            CompactEvalGateway gateway,
            Duration timeout,
            List<CompactEvalRunArtifact.TurnResult> turns)
            throws Exception {
        for (CompactEvalCorpus.Probe probe : scenario.probes()) {
            CompactEvalGateway.TurnResult result =
                    gateway.submitAndAwait(sessionId, probe.question(), timeout);
            turns.add(
                    toArtifactTurn(
                            turns.size(),
                            PROBE_KIND,
                            probe.question(),
                            probe.expectedAnswer(),
                            result));
            if (!DONE_STATUS.equals(result.status())) {
                return false;
            }
        }
        return true;
    }

    private CompactEvalRunArtifact.TurnResult toArtifactTurn(
            int index,
            String kind,
            String input,
            String expectedAnswer,
            CompactEvalGateway.TurnResult result) {
        Boolean matched =
                expectedAnswer == null
                        ? null
                        : normalize(result.assistantAnswer()).contains(normalize(expectedAnswer));
        return new CompactEvalRunArtifact.TurnResult(
                index,
                kind,
                input,
                expectedAnswer,
                matched,
                result.executionId(),
                result.status(),
                result.assistantAnswer(),
                result.messageCount(),
                result.compactionStartedCount(),
                result.compactionCompletedCount(),
                result.compactionFailedCount(),
                result.durationMillis());
    }

    /** 只用于产生可解释的本地基线，不替代语义裁判或人工复核。 */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
