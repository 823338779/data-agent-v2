package com.datagent.agentv2.conversation.session;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_DETAIL_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_DETAIL_EXECUTION_STARTED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_TITLE_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_TITLE_EXECUTION_STARTED;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
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
        List<MessageView> messages,
        AgentExecutionView execution) {

    /** 从尚无消息和执行记录的新建会话组装完整接口投影。 */
    public static ConversationView composeFromNewSession(ConversationSession session) {
        return composeFromSession(session, List.of(), null);
    }

    /** 从会话及其已组装的关联视图生成完整接口投影。 */
    public static ConversationView composeFromSession(
            ConversationSession session,
            List<MessageView> messages,
            AgentExecutionView execution) {
        return new ConversationView(
                session.getConversationSessionId(),
                session.getTitle(),
                session.getTag() == null ? "" : session.getTag(),
                ConversationSessionType.fromValue(session.getSessionType()),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getVersion(),
                0,
                null,
                messages,
                execution);
    }

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
            AgentExecutionView execution) {

        /** 从消息及其可选执行详情组装公开消息。 */
        public static MessageView composeFromMessage(
                ConversationMessage message, AgentExecutionView execution) {
            String author =
                    message.getAuthorUserId() == null
                            ? message.getRole()
                            : message.getAuthorUserId();
            return new MessageView(
                    message.getConversationMessageId(),
                    message.getExecutionId(),
                    MessageRole.fromValue(message.getRole()),
                    author,
                    message.getContent(),
                    java.util.Objects.requireNonNullElse(message.getVersion(), 0L),
                    message.getThinkingProcess(),
                    message.getCreatedAt(),
                    List.of(),
                    execution);
        }
    }

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
            AgentExecutionStreamCheckpointView streamCheckpoint) {

        /** 从执行、关联投影和检查点组装完整执行视图。 */
        public static AgentExecutionView composeFromExecution(
                AgentExecution execution,
                String objective,
                List<AgentExecutionEventView> events,
                AgentExecutionStreamCheckpoint checkpoint) {
            return new AgentExecutionView(
                    execution.getExecutionId(),
                    ExecutionStatus.fromValue(execution.getStatus()),
                    ConversationView.progress(execution.getPhase()),
                    objective,
                    execution.getCancellationRequestedAt(),
                    events,
                    null,
                    null,
                    AgentExecutionStreamCheckpointView.composeFromCheckpoint(checkpoint));
        }
    }

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
            AgentExecutionStreamCheckpointView streamCheckpoint) {

        /** 从执行、目标及最新检查点组装轻量状态。 */
        public static AgentExecutionStateView composeFromExecution(
                AgentExecution execution,
                String objective,
                AgentExecutionStreamCheckpoint checkpoint) {
            return new AgentExecutionStateView(
                    execution.getExecutionId(),
                    ExecutionStatus.fromValue(execution.getStatus()),
                    ConversationView.progress(execution.getPhase()),
                    objective,
                    execution.getCancellationRequestedAt(),
                    AgentExecutionStreamCheckpointView.composeFromCheckpoint(checkpoint));
        }
    }

    /** 一条可由前端幂等归约的执行更新，包含状态、单个时间线事件和相关可见消息。 */
    public record AgentExecutionUpdateView(
            /** 更新所属会话标识。 */
            String conversationSessionId,
            /** 更新后的轻量执行状态。 */
            AgentExecutionStateView execution,
            /** 本次新增的持久执行事件。 */
            AgentExecutionEventView event,
            /** 本次执行当前已经产生的用户可见消息。 */
            List<MessageView> messages) {

        /** 从一次执行变化及其相关消息组装 SSE 更新。 */
        public static AgentExecutionUpdateView composeFromEvent(
                AgentExecution execution,
                AgentExecutionEvent event,
                String objective,
                AgentExecutionStreamCheckpoint checkpoint,
                List<MessageView> messages) {
            return new AgentExecutionUpdateView(
                    execution.getConversationSessionId(),
                    AgentExecutionStateView.composeFromExecution(
                            execution, objective, checkpoint),
                    AgentExecutionEventView.composeFromEvent(event, execution),
                    messages);
        }
    }

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
            ExecutionStatus status) {

        /** 从持久事件及其所属执行组装公开事件。 */
        public static AgentExecutionEventView composeFromEvent(
                AgentExecutionEvent event, AgentExecution execution) {
            boolean queued = ExecutionEventType.QUEUED.value().equals(event.getType());
            return new AgentExecutionEventView(
                    event.getExecutionEventId(),
                    event.getExecutionEventSequence(),
                    ExecutionEventType.fromValue(event.getType()),
                    event.getOccurredAt(),
                    event.getExecutionId(),
                    execution.getTriggeringConversationMessageId(),
                    queued ? EVENT_TITLE_EXECUTION_QUEUED : EVENT_TITLE_EXECUTION_STARTED,
                    queued ? EVENT_DETAIL_EXECUTION_QUEUED : EVENT_DETAIL_EXECUTION_STARTED,
                    null,
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    ExecutionStatus.fromValue(execution.getStatus()));
        }
    }

    /** 用于断线恢复的最新流式输出检查点。 */
    public record AgentExecutionStreamCheckpointView(
            long executionAttemptNumber,
            long modelInvocationSequence,
            ExecutionPhase phase,
            long outputDeltaSequence,
            String answer) {

        /** 从持久流检查点组装公开检查点；不存在检查点时返回空。 */
        public static AgentExecutionStreamCheckpointView composeFromCheckpoint(
                AgentExecutionStreamCheckpoint checkpoint) {
            return checkpoint == null
                    ? null
                    : new AgentExecutionStreamCheckpointView(
                            checkpoint.getExecutionAttemptNumber(),
                            checkpoint.getModelInvocationSequence(),
                            ExecutionPhase.fromValue(checkpoint.getPhase()),
                            checkpoint.getOutputDeltaSequence(),
                            checkpoint.getAnswer());
        }
    }

    private static int progress(String phase) {
        ExecutionPhase executionPhase;
        try {
            executionPhase = ExecutionPhase.fromValue(phase);
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
        return switch (executionPhase) {
            case QUEUED -> 0;
            case RECEIVED -> 5;
            case PREPARING_CONTEXT -> 15;
            case CONNECTING_MODEL -> 25;
            case ANSWERING -> 70;
            case PERSISTING -> 90;
            case COMPLETED -> 100;
        };
    }
}
