package com.datagent.agentv2.conversation.session;

import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.enums.ConversationSessionType;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.enums.MessageRole;
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
            /** 消息关联的 Agent 执行标识；用户消息和尚未关联的消息允许为空。 */
            String executionId,
            MessageRole role,
            String author,
            String content,
            /** 消息乐观锁版本，供 SSE 全量替换进行幂等去重。 */
            long version,
            /** 与正文绑定的有序思考及工具过程节点。 */
            List<ThinkingProcessItem> thinkingProcess,
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

    /** SSE 生命周期更新携带的轻量执行状态，不重复发送完整历史事件集合。 */
    public record AgentExecutionStateView(
            /** Agent 执行业务标识。 */
            String executionId,
            /** 当前执行状态。 */
            ExecutionStatus status,
            /** 当前执行阶段映射出的展示进度。 */
            int progress,
            /** 触发执行的用户目标。 */
            String objective,
            /** 首次取消请求时间。 */
            Instant cancellationRequestedAt,
            /** 当前持久化回答检查点。 */
            AgentExecutionStreamCheckpointView streamCheckpoint) {}

    /** 一条可由前端幂等归约的执行更新，包含状态、单个时间线事件和相关可见消息。 */
    public record AgentExecutionUpdateView(
            /** 更新所属会话标识。 */
            String conversationSessionId,
            /** 更新后的轻量执行状态。 */
            AgentExecutionStateView execution,
            /** 本次新增的持久执行事件。 */
            AgentExecutionEventView event,
            /** 本次执行当前已经产生的用户可见消息。 */
            List<MessageView> messages) {}

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
            String answer) {}
}
