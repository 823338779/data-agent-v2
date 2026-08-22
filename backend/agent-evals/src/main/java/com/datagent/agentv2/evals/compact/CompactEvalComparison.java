package com.datagent.agentv2.evals.compact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对两次独立服务运行的产物做离线对账，不调用指标平台或产品内部接口。 */
public final class CompactEvalComparison {

    private static final String COMPARISON_SCHEMA_VERSION = "compact-eval-comparison/v1";
    private static final String PROBE_KIND = "probe";
    private static final String DONE_STATUS = "done";

    /** 要求 A/B 来自同一语料并保持相同场景、轮次和输入顺序。 */
    public ComparisonArtifact compare(CompactEvalRunArtifact left, CompactEvalRunArtifact right) {
        if (!left.corpusSource().equals(right.corpusSource())) {
            throw new IllegalArgumentException(
                    "Compact evaluation artifacts use different corpora");
        }
        if (left.scenarios().size() != right.scenarios().size()) {
            throw new IllegalArgumentException(
                    "Compact evaluation artifacts have different scenario counts");
        }

        // 逐轮核对可以防止把缺轮、错序或不同问题的回答误当成机制差异。
        List<ProbePair> probes = new ArrayList<>();
        for (int scenarioIndex = 0; scenarioIndex < left.scenarios().size(); scenarioIndex++) {
            CompactEvalRunArtifact.ScenarioResult leftScenario =
                    left.scenarios().get(scenarioIndex);
            CompactEvalRunArtifact.ScenarioResult rightScenario =
                    right.scenarios().get(scenarioIndex);
            requireEqual(leftScenario.scenarioId(), rightScenario.scenarioId(), "scenario id");
            int commonTurns = Math.min(leftScenario.turns().size(), rightScenario.turns().size());
            for (int turnIndex = 0; turnIndex < commonTurns; turnIndex++) {
                CompactEvalRunArtifact.TurnResult leftTurn = leftScenario.turns().get(turnIndex);
                CompactEvalRunArtifact.TurnResult rightTurn = rightScenario.turns().get(turnIndex);
                requireEqual(leftTurn.kind(), rightTurn.kind(), "turn kind");
                requireEqual(leftTurn.input(), rightTurn.input(), "turn input");
            }
            probes.addAll(probePairs(leftScenario, rightScenario));
        }
        return new ComparisonArtifact(
                COMPARISON_SCHEMA_VERSION,
                left.corpusSource(),
                summary(left),
                summary(right),
                List.copyOf(probes));
    }

    /** 汇总只提供快速检查，最终判断仍可使用报告内保留的原始回答。 */
    private MechanismSummary summary(CompactEvalRunArtifact artifact) {
        int completedScenarios = 0;
        int completedTurns = 0;
        int probeCount = 0;
        int referenceMatches = 0;
        int compactionStartedCount = 0;
        int compactionCompletedCount = 0;
        int compactionFailedCount = 0;
        for (CompactEvalRunArtifact.ScenarioResult scenario : artifact.scenarios()) {
            if (scenario.completed()) {
                completedScenarios++;
            }
            for (CompactEvalRunArtifact.TurnResult turn : scenario.turns()) {
                if (DONE_STATUS.equals(turn.status())) {
                    completedTurns++;
                }
                if (PROBE_KIND.equals(turn.kind())) {
                    probeCount++;
                    if (Boolean.TRUE.equals(turn.referenceMatched())) {
                        referenceMatches++;
                    }
                }
                compactionStartedCount += turn.compactionStartedCount();
                compactionCompletedCount += turn.compactionCompletedCount();
                compactionFailedCount += turn.compactionFailedCount();
            }
        }
        return new MechanismSummary(
                artifact.mechanism(),
                artifact.scenarios().size(),
                completedScenarios,
                completedTurns,
                probeCount,
                referenceMatches,
                compactionStartedCount,
                compactionCompletedCount,
                compactionFailedCount,
                artifact.durationMillis());
    }

    /** 允许某一机制提前停止；缺失侧保留 null，使严重失败仍能进入最终报告。 */
    private List<ProbePair> probePairs(
            CompactEvalRunArtifact.ScenarioResult left,
            CompactEvalRunArtifact.ScenarioResult right) {
        Map<String, CompactEvalRunArtifact.TurnResult> leftProbes = probesByQuestion(left);
        Map<String, CompactEvalRunArtifact.TurnResult> rightProbes = probesByQuestion(right);
        Set<String> questions = new LinkedHashSet<>(leftProbes.keySet());
        questions.addAll(rightProbes.keySet());
        List<ProbePair> pairs = new ArrayList<>();
        for (String question : questions) {
            pairs.add(
                    toPair(
                            left.scenarioId(),
                            question,
                            leftProbes.get(question),
                            rightProbes.get(question)));
        }
        return pairs;
    }

    private Map<String, CompactEvalRunArtifact.TurnResult> probesByQuestion(
            CompactEvalRunArtifact.ScenarioResult scenario) {
        Map<String, CompactEvalRunArtifact.TurnResult> probes = new LinkedHashMap<>();
        for (CompactEvalRunArtifact.TurnResult turn : scenario.turns()) {
            if (PROBE_KIND.equals(turn.kind()) && probes.put(turn.input(), turn) != null) {
                throw new IllegalArgumentException(
                        "Scenario contains duplicate probe question: " + scenario.scenarioId());
            }
        }
        return probes;
    }

    private ProbePair toPair(
            String scenarioId,
            String question,
            CompactEvalRunArtifact.TurnResult left,
            CompactEvalRunArtifact.TurnResult right) {
        String expectedAnswer = left == null ? right.expectedAnswer() : left.expectedAnswer();
        if (left != null && right != null) {
            requireEqual(left.expectedAnswer(), right.expectedAnswer(), "expected answer");
        }
        return new ProbePair(
                scenarioId,
                question,
                expectedAnswer,
                left == null ? null : left.referenceMatched(),
                right == null ? null : right.referenceMatched(),
                left == null ? null : left.assistantAnswer(),
                right == null ? null : right.assistantAnswer());
    }

    private static void requireEqual(String left, String right, String field) {
        if (!java.util.Objects.equals(left, right)) {
            throw new IllegalArgumentException("Compact evaluation artifacts differ at " + field);
        }
    }

    /** 可直接写入 JSON 的离线对比报告。 */
    public record ComparisonArtifact(
            String schemaVersion,
            String corpusSource,
            MechanismSummary left,
            MechanismSummary right,
            List<ProbePair> probes) {}

    /** 单机制的本地执行和宽松参考命中汇总。 */
    public record MechanismSummary(
            String mechanism,
            int scenarioCount,
            int completedScenarios,
            int completedTurns,
            int probeCount,
            int referenceMatches,
            int compactionStartedCount,
            int compactionCompletedCount,
            int compactionFailedCount,
            long durationMillis) {}

    /** 同一道探针在两个机制下的原始回答与参考命中结果。 */
    public record ProbePair(
            String scenarioId,
            String question,
            String expectedAnswer,
            Boolean leftReferenceMatched,
            Boolean rightReferenceMatched,
            String leftAnswer,
            String rightAnswer) {}
}
