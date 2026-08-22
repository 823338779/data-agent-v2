package com.datagent.agentv2.evals.compact;

import java.time.Duration;

/** 回放器使用的最小会话网关，可由公开 HTTP 实现或单元测试替身实现。 */
public interface CompactEvalGateway {

    /** 创建一条与其他评测运行隔离的新会话。 */
    String createSession(String title) throws Exception;

    /** 提交一轮输入并等待该轮 Agent 执行进入终态。 */
    TurnResult submitAndAwait(String sessionId, String content, Duration timeout) throws Exception;

    /** 一轮执行完成后的公开结果快照。 */
    record TurnResult(
            String executionId,
            String status,
            String assistantAnswer,
            int messageCount,
            int compactionStartedCount,
            int compactionCompletedCount,
            int compactionFailedCount,
            long durationMillis) {}
}
