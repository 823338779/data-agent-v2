package com.datagent.agentv2.evals.compact;

import java.util.List;

/** 一次单机制评测的可持久化原始产物，可在服务重启后与另一机制离线比较。 */
public record CompactEvalRunArtifact(
        String schemaVersion,
        String mechanism,
        String corpusSource,
        String startedAt,
        long durationMillis,
        List<ScenarioResult> scenarios) {

    /** 单场景的服务会话标识、完成状态及逐轮公开结果。 */
    public record ScenarioResult(
            String scenarioId,
            String sessionId,
            boolean completed,
            /** 回放器或服务异常摘要；正常完成或业务终态失败时为空。 */
            String failure,
            List<TurnResult> turns) {}

    /** 一轮输入与回答的完整对账信息。 */
    public record TurnResult(
            int index,
            String kind,
            String input,
            String expectedAnswer,
            Boolean referenceMatched,
            String executionId,
            String status,
            String assistantAnswer,
            int messageCount,
            int compactionStartedCount,
            int compactionCompletedCount,
            int compactionFailedCount,
            long durationMillis) {}
}
