package com.datagent.agentv2.conversation.session;

import com.datagent.agentv2.conversation.execution.ExecutionEventType;
import com.datagent.agentv2.conversation.execution.ExecutionPhase;
import com.datagent.agentv2.conversation.execution.ExecutionStatus;
import com.datagent.agentv2.conversation.message.MessageRole;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 对话查询接口返回的完整会话投影。 */
public record ConversationView(
        String conversationSessionId,
        String title,
        String tag,
        ConversationSessionType sessionType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version,
        long metricPlatformCredentialBindingVersion,
        Object metricPlatformCredential,
        boolean unread,
        long lastNotifiableEventSequence,
        long sortOrder,
        long orderingVersion,
        List<MessageView> messages,
        AgentExecutionView execution) {

    public record MessageView(
            String conversationMessageId,
            MessageRole role,
            String author,
            String content,
            LocalDateTime createdAt,
            List<Object> citations,
            /** assistant 回答所属的 Agent 执行，用于在历史消息下恢复对应过程。 */
            AgentExecutionView execution) {}

    /** 一次 Agent 执行的公开状态、事件时间线和最新输出检查点。 */
    public record AgentExecutionView(
            String executionId,
            ExecutionStatus status,
            int progress,
            String objective,
            Instant cancellationRequestedAt,
            List<AgentExecutionEventView> events,
            Object pendingConfirmation,
            Object pendingInteraction,
            AgentExecutionStreamCheckpointView streamCheckpoint) {}

    /** Agent 执行时间线中的一个持久化公开事件。 */
    public record AgentExecutionEventView(
            String executionEventId,
            long executionEventSequence,
            ExecutionEventType type,
            Instant occurredAt,
            String executionId,
            String conversationMessageId,
            String title,
            String summary,
            String toolName,
            Map<String, Object> input,
            Map<String, Object> output,
            String confirmationId,
            Boolean approved,
            ExecutionStatus status) {}

    /** 用于断线恢复的最新流式输出检查点。 */
    public record AgentExecutionStreamCheckpointView(
            long executionAttemptNumber,
            long modelInvocationSequence,
            ExecutionPhase phase,
            long outputDeltaSequence,
            String thinking,
            String answer) {}
}
