package com.datagent.agentv2.evals.compact;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 针对当前已启动服务顺序执行语料，并生成包含评分的独立评测结果。 */
public final class CompactEvalRunner {

    private static final String REPORT_SCHEMA_VERSION = "compact-eval-report/v4";
    private static final String REFERENCE_SCORING_METHOD = "normalized-reference-containment/v1";
    private static final double SEMANTIC_PASS_THRESHOLD = 0.75;
    private static final String HISTORY_KIND = "history";
    private static final String PROBE_KIND = "probe";
    private static final String DONE_STATUS = "done";
    private static final String JUDGE_COMPLETED_STATUS = "completed";
    private static final String JUDGE_FAILED_STATUS = "failed";
    private static final String JUDGE_SKIPPED_STATUS = "skipped";
    private static final String OFFICIAL_COMPLETED_STATUS = "completed";

    private final CompactEvalOfficialScorer officialScorer = new CompactEvalOfficialScorer();

    /** 回放指定数量的场景；maxScenarios 小于一时表示不限制。 */
    public CompactEvalReport run(
            String configurationLabel,
            String target,
            CompactEvalCorpus corpus,
            CompactEvalGateway gateway,
            CompactEvalJudge judge,
            Duration turnTimeout,
            int maxScenarios)
            throws Exception {
        Instant startedAt = Instant.now();
        int scenarioLimit =
                maxScenarios < 1
                        ? corpus.scenarios().size()
                        : Math.min(maxScenarios, corpus.scenarios().size());
        List<CompactEvalReport.ScenarioResult> scenarios = new ArrayList<>();

        // 每个场景独占一条产品会话，场景失败后继续保留已经完成的原始轮次。
        for (int scenarioIndex = 0; scenarioIndex < scenarioLimit; scenarioIndex++) {
            CompactEvalCorpus.Scenario scenario = corpus.scenarios().get(scenarioIndex);
            String sessionId = null;
            List<CompactEvalReport.TurnResult> turns = new ArrayList<>();
            boolean completed = false;
            String failure = null;
            try {
                sessionId = gateway.createSession(scenario.title());
                completed = replayHistory(scenario, sessionId, gateway, turnTimeout, turns);
                if (completed) {
                    completed =
                            replayProbes(scenario, sessionId, gateway, judge, turnTimeout, turns);
                }
            } catch (Exception scenarioFailure) {
                failure =
                        scenarioFailure.getClass().getSimpleName()
                                + ": "
                                + scenarioFailure.getMessage();
            }
            scenarios.add(
                    new CompactEvalReport.ScenarioResult(
                            scenario.id(),
                            scenario.metadata(),
                            sessionId,
                            completed,
                            failure,
                            List.copyOf(turns)));
        }
        long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
        return new CompactEvalReport(
                REPORT_SCHEMA_VERSION,
                new CompactEvalReport.ServerConfiguration(configurationLabel, target),
                new CompactEvalReport.JudgeConfiguration(
                        judge.provider(),
                        judge.model(),
                        judge.effort(),
                        judge.promptVersion(),
                        judge.rubricVersion()),
                corpus.source(),
                startedAt.toString(),
                durationMillis,
                summarize(corpus, scenarioLimit, scenarios),
                List.copyOf(scenarios));
    }

    /** 历史轮只负责推动真实上下文增长；任一执行失败即停止当前场景。 */
    private boolean replayHistory(
            CompactEvalCorpus.Scenario scenario,
            String sessionId,
            CompactEvalGateway gateway,
            Duration timeout,
            List<CompactEvalReport.TurnResult> turns)
            throws Exception {
        for (String history : scenario.history()) {
            CompactEvalGateway.TurnResult result =
                    gateway.submitAndAwait(sessionId, history, timeout);
            turns.add(
                    toReportTurn(
                            turns.size(),
                            HISTORY_KIND,
                            history,
                            null,
                            scenario.metadata(),
                            result,
                            null));
            if (!DONE_STATUS.equals(result.status())) {
                return false;
            }
        }
        return true;
    }

    /** 探针轮调用独立 LLM Judge，并保留参考答案、字符串基线和原始回答。 */
    private boolean replayProbes(
            CompactEvalCorpus.Scenario scenario,
            String sessionId,
            CompactEvalGateway gateway,
            CompactEvalJudge judge,
            Duration timeout,
            List<CompactEvalReport.TurnResult> turns)
            throws Exception {
        for (CompactEvalCorpus.Probe probe : scenario.probes()) {
            CompactEvalGateway.TurnResult result =
                    gateway.submitAndAwait(sessionId, probe.question(), timeout);
            turns.add(
                    toReportTurn(
                            turns.size(),
                            PROBE_KIND,
                            probe.question(),
                            probe.expectedAnswer(),
                            scenario.metadata(),
                            result,
                            judge));
            if (!DONE_STATUS.equals(result.status())) {
                return false;
            }
        }
        return true;
    }

    private CompactEvalReport.TurnResult toReportTurn(
            int index,
            String kind,
            String input,
            String expectedAnswer,
            CompactEvalCorpus.ScenarioMetadata metadata,
            CompactEvalGateway.TurnResult result,
            CompactEvalJudge judge) {
        CompactEvalReport.Evaluation evaluation =
                expectedAnswer == null
                        ? null
                        : evaluate(input, expectedAnswer, metadata, result, judge);
        return new CompactEvalReport.TurnResult(
                index,
                kind,
                input,
                expectedAnswer,
                evaluation,
                result.executionId(),
                result.status(),
                result.assistantAnswer(),
                result.messageCount(),
                result.compactionStartedCount(),
                result.compactionCompletedCount(),
                result.compactionFailedCount(),
                result.durationMillis());
    }

    private CompactEvalReport.Evaluation evaluate(
            String question,
            String expectedAnswer,
            CompactEvalCorpus.ScenarioMetadata metadata,
            CompactEvalGateway.TurnResult result,
            CompactEvalJudge judge) {
        boolean matched =
                DONE_STATUS.equals(result.status())
                        && normalize(result.assistantAnswer()).contains(normalize(expectedAnswer));
        CompactEvalReport.ReferenceBaseline baseline =
                new CompactEvalReport.ReferenceBaseline(
                        REFERENCE_SCORING_METHOD, matched ? 1.0 : 0.0, matched);
        CompactEvalReport.OfficialScore officialScore;
        CompactEvalReport.SemanticJudgment semanticJudgment;
        if (!DONE_STATUS.equals(result.status())) {
            officialScore =
                    officialScorer.skipped(
                            metadata, "Probe execution status is " + result.status());
            semanticJudgment =
                    new CompactEvalReport.SemanticJudgment(
                            judge.provider(),
                            judge.model(),
                            JUDGE_SKIPPED_STATUS,
                            null,
                            null,
                            null,
                            null,
                            "Probe execution status is " + result.status());
        } else {
            try {
                CompactEvalJudge.Judgment judged =
                        judge.judge(
                                new CompactEvalJudge.JudgeRequest(
                                        question,
                                        expectedAnswer,
                                        result.assistantAnswer(),
                                        metadata));
                officialScore =
                        officialScorer.score(
                                metadata, expectedAnswer, result.assistantAnswer(), judged);
                semanticJudgment =
                        new CompactEvalReport.SemanticJudgment(
                                judge.provider(),
                                judge.model(),
                                JUDGE_COMPLETED_STATUS,
                                judged.score(),
                                judged.score() >= SEMANTIC_PASS_THRESHOLD,
                                judged.correct(),
                                judged.reason(),
                                null);
            } catch (Exception failure) {
                officialScore =
                        "LongMemEval".equals(metadata == null ? null : metadata.dataset())
                                ? officialScorer.failed(
                                        metadata,
                                        failure.getClass().getSimpleName()
                                                + ": "
                                                + failure.getMessage())
                                : officialScorer.scoreWithoutJudge(
                                        metadata, expectedAnswer, result.assistantAnswer());
                semanticJudgment =
                        new CompactEvalReport.SemanticJudgment(
                                judge.provider(),
                                judge.model(),
                                JUDGE_FAILED_STATUS,
                                null,
                                null,
                                null,
                                null,
                                failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        return new CompactEvalReport.Evaluation(officialScore, baseline, semanticJudgment);
    }

    private CompactEvalReport.ScoreSummary summarize(
            CompactEvalCorpus corpus,
            int scenarioLimit,
            List<CompactEvalReport.ScenarioResult> scenarios) {
        int completedScenarioCount = 0;
        int expectedTurnCount = 0;
        int completedTurnCount = 0;
        int expectedProbeCount = 0;
        int executedProbeCount = 0;
        int officialEligibleProbeCount = 0;
        int officialScoredProbeCount = 0;
        double officialScoreTotal = 0.0;
        int semanticJudgedProbeCount = 0;
        int semanticPassCount = 0;
        double semanticScoreTotal = 0.0;
        int judgeFailureCount = 0;
        int referenceMatchCount = 0;
        int compactionStartedCount = 0;
        int compactionCompletedCount = 0;
        int compactionFailedCount = 0;
        Map<DimensionKey, DimensionAccumulator> dimensions = new LinkedHashMap<>();

        for (int index = 0; index < scenarioLimit; index++) {
            CompactEvalCorpus.Scenario source = corpus.scenarios().get(index);
            CompactEvalReport.ScenarioResult result = scenarios.get(index);
            expectedTurnCount += source.history().size() + source.probes().size();
            expectedProbeCount += source.probes().size();
            boolean officialEligible = officialScorer.supports(source.metadata());
            if (officialEligible) {
                officialEligibleProbeCount += source.probes().size();
            }
            List<DimensionAccumulator> scenarioDimensions =
                    dimensionsFor(
                            source.metadata(),
                            source.probes().size(),
                            officialEligible,
                            dimensions);
            if (result.completed()) {
                completedScenarioCount++;
            }
            for (CompactEvalReport.TurnResult turn : result.turns()) {
                if (DONE_STATUS.equals(turn.status())) {
                    completedTurnCount++;
                }
                if (PROBE_KIND.equals(turn.kind())) {
                    executedProbeCount++;
                    CompactEvalReport.Evaluation evaluation = turn.evaluation();
                    scenarioDimensions.forEach(dimension -> dimension.recordExecuted(evaluation));
                    if (evaluation.baseline().matched()) {
                        referenceMatchCount++;
                    }
                    if (OFFICIAL_COMPLETED_STATUS.equals(evaluation.officialScore().status())) {
                        officialScoredProbeCount++;
                        officialScoreTotal += evaluation.officialScore().score();
                    }
                    if (JUDGE_COMPLETED_STATUS.equals(evaluation.semanticJudge().status())) {
                        semanticJudgedProbeCount++;
                        semanticScoreTotal += evaluation.semanticJudge().score();
                        if (Boolean.TRUE.equals(evaluation.semanticJudge().passed())) {
                            semanticPassCount++;
                        }
                    } else if (JUDGE_FAILED_STATUS.equals(evaluation.semanticJudge().status())) {
                        judgeFailureCount++;
                    }
                }
                compactionStartedCount += turn.compactionStartedCount();
                compactionCompletedCount += turn.compactionCompletedCount();
                compactionFailedCount += turn.compactionFailedCount();
            }
        }
        return new CompactEvalReport.ScoreSummary(
                scenarioLimit,
                completedScenarioCount,
                rate(completedScenarioCount, scenarioLimit),
                expectedTurnCount,
                completedTurnCount,
                rate(completedTurnCount, expectedTurnCount),
                expectedProbeCount,
                executedProbeCount,
                officialEligibleProbeCount,
                officialScoredProbeCount,
                officialEligibleProbeCount == 0
                        ? 0.0
                        : officialScoreTotal / officialEligibleProbeCount,
                semanticJudgedProbeCount,
                semanticPassCount,
                expectedProbeCount == 0 ? 0.0 : semanticScoreTotal / expectedProbeCount,
                judgeFailureCount,
                referenceMatchCount,
                rate(referenceMatchCount, expectedProbeCount),
                compactionStartedCount,
                compactionCompletedCount,
                compactionFailedCount,
                dimensions.values().stream().map(DimensionAccumulator::toReport).toList());
    }

    private static List<DimensionAccumulator> dimensionsFor(
            CompactEvalCorpus.ScenarioMetadata metadata,
            int expectedProbeCount,
            boolean officialEligible,
            Map<DimensionKey, DimensionAccumulator> dimensions) {
        if (metadata == null) {
            return List.of();
        }
        Set<DimensionKey> keys = new LinkedHashSet<>();
        addDimension(keys, "dataset", metadata.dataset());
        addDimension(keys, "subset", metadata.subset());
        addDimension(keys, "questionType", metadata.questionType());
        addDimension(keys, "reasoningType", metadata.reasoningType());
        addDimension(keys, "difficulty", metadata.difficulty());
        addDimension(keys, "abstention", Boolean.toString(metadata.abstention()));
        for (CompactEvalCorpus.Evidence evidence : metadata.evidence()) {
            addDimension(keys, "evidencePositionPeriod", evidence.positionPeriod());
            addDimension(
                    keys,
                    "evidencePositionSegment",
                    evidence.positionSegment() == null
                            ? null
                            : Integer.toString(evidence.positionSegment()));
            addDimension(keys, "evidenceDate", evidence.date());
            addDimension(
                    keys,
                    "evidenceMemoryAgeDays",
                    evidence.memoryAgeDays() == null
                            ? null
                            : Long.toString(evidence.memoryAgeDays()));
        }
        List<DimensionAccumulator> scenarioDimensions = new ArrayList<>();
        for (DimensionKey key : keys) {
            DimensionAccumulator accumulator =
                    dimensions.computeIfAbsent(key, DimensionAccumulator::new);
            accumulator.expectedProbeCount += expectedProbeCount;
            if (officialEligible) {
                accumulator.officialEligibleProbeCount += expectedProbeCount;
            }
            scenarioDimensions.add(accumulator);
        }
        return List.copyOf(scenarioDimensions);
    }

    private static void addDimension(Set<DimensionKey> keys, String dimension, String value) {
        if (value != null && !value.isBlank()) {
            keys.add(new DimensionKey(dimension, value));
        }
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
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

    private record DimensionKey(String dimension, String value) {}

    private static final class DimensionAccumulator {

        private final DimensionKey key;
        private int expectedProbeCount;
        private int executedProbeCount;
        private int officialEligibleProbeCount;
        private int officialScoredProbeCount;
        private double officialScoreTotal;
        private int semanticJudgedProbeCount;
        private int semanticPassCount;
        private double semanticScoreTotal;
        private int referenceMatchCount;

        private DimensionAccumulator(DimensionKey key) {
            this.key = key;
        }

        private void recordExecuted(CompactEvalReport.Evaluation evaluation) {
            executedProbeCount++;
            if (evaluation.baseline().matched()) {
                referenceMatchCount++;
            }
            if (OFFICIAL_COMPLETED_STATUS.equals(evaluation.officialScore().status())) {
                officialScoredProbeCount++;
                officialScoreTotal += evaluation.officialScore().score();
            }
            if (JUDGE_COMPLETED_STATUS.equals(evaluation.semanticJudge().status())) {
                semanticJudgedProbeCount++;
                semanticScoreTotal += evaluation.semanticJudge().score();
                if (Boolean.TRUE.equals(evaluation.semanticJudge().passed())) {
                    semanticPassCount++;
                }
            }
        }

        private CompactEvalReport.DimensionScore toReport() {
            return new CompactEvalReport.DimensionScore(
                    key.dimension(),
                    key.value(),
                    expectedProbeCount,
                    executedProbeCount,
                    officialEligibleProbeCount,
                    officialScoredProbeCount,
                    officialEligibleProbeCount == 0
                            ? 0.0
                            : officialScoreTotal / officialEligibleProbeCount,
                    semanticJudgedProbeCount,
                    semanticPassCount,
                    expectedProbeCount == 0 ? 0.0 : semanticScoreTotal / expectedProbeCount,
                    referenceMatchCount,
                    rate(referenceMatchCount, expectedProbeCount));
        }
    }
}
