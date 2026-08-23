package com.datagent.agentv2.evals.compact;

import java.util.List;

/** 当前服务端配置的一次独立评测结果文档，可交给外部系统做跨配置比较。 */
public record CompactEvalReport(
        String schemaVersion,
        ServerConfiguration serverConfiguration,
        JudgeConfiguration judgeConfiguration,
        String corpusSource,
        String startedAt,
        long durationMillis,
        ScoreSummary score,
        List<ScenarioResult> scenarios) {

    /** 仅标识本次被测服务，不读取或推断服务端内部配置。 */
    public record ServerConfiguration(String label, String target) {}

    /** 固化本次语义裁判配置，供外部复现同一评分协议。 */
    public record JudgeConfiguration(
            String provider,
            String model,
            String effort,
            String promptVersion,
            String rubricVersion) {}

    /** 本次运行自身的完成度、LLM 裁判得分和字符串基线，不包含其他运行的数据。 */
    public record ScoreSummary(
            int scenarioCount,
            int completedScenarioCount,
            double scenarioCompletionRate,
            int expectedTurnCount,
            int completedTurnCount,
            double turnCompletionRate,
            int expectedProbeCount,
            int executedProbeCount,
            int officialEligibleProbeCount,
            int officialScoredProbeCount,
            double officialScore,
            int semanticJudgedProbeCount,
            int semanticPassCount,
            double semanticJudgeScore,
            int judgeFailureCount,
            int referenceMatchCount,
            double referenceMatchScore,
            int compactionStartedCount,
            int compactionCompletedCount,
            int compactionFailedCount,
            List<DimensionScore> dimensions) {}

    /** 按数据集原始标签和统一位置标签拆分的探针得分，供外部直接比较。 */
    public record DimensionScore(
            String dimension,
            String value,
            int expectedProbeCount,
            int executedProbeCount,
            int officialEligibleProbeCount,
            int officialScoredProbeCount,
            double officialScore,
            int semanticJudgedProbeCount,
            int semanticPassCount,
            double semanticJudgeScore,
            int referenceMatchCount,
            double referenceMatchScore) {}

    /** 单场景的服务会话标识、完成状态及逐轮公开结果。 */
    public record ScenarioResult(
            String scenarioId,
            CompactEvalCorpus.ScenarioMetadata metadata,
            String sessionId,
            boolean completed,
            /** 回放器或服务异常摘要；正常完成或业务终态失败时为空。 */
            String failure,
            List<TurnResult> turns) {}

    /** 一轮输入、服务输出以及该轮自身的评分证据。 */
    public record TurnResult(
            int index,
            String kind,
            String input,
            String expectedAnswer,
            Evaluation evaluation,
            String executionId,
            String status,
            String assistantAnswer,
            int messageCount,
            int compactionStartedCount,
            int compactionCompletedCount,
            int compactionFailedCount,
            long durationMillis) {}

    /** 探针回答的数据集协议主分、辅助语义分和可复现字符串基线。 */
    public record Evaluation(
            OfficialScore officialScore,
            ReferenceBaseline baseline,
            SemanticJudgment semanticJudge) {}

    /** status 为 completed、failed、skipped 或 not-applicable。 */
    public record OfficialScore(
            String method,
            String status,
            Double score,
            Boolean passed,
            String reason,
            String failure) {}

    public record ReferenceBaseline(String method, double score, boolean matched) {}

    /** status 为 completed、failed 或 skipped；失败和跳过时 score、passed 为空。 */
    public record SemanticJudgment(
            String provider,
            String model,
            String status,
            Double score,
            Boolean passed,
            Boolean correct,
            String reason,
            String failure) {}
}
