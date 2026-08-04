package com.datagent.agentv2.conversation.domain;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 用户、模型或工具产生的一条不可变会话消息。 */
@Getter
@Setter
@NoArgsConstructor
public final class ConversationMessage {

    /** 消息业务唯一标识。 */
    private String conversationMessageId;

    /** 消息所属会话标识。 */
    private String conversationSessionId;

    /** 产生或消费消息的 Agent 执行标识。 */
    private String executionId;

    /** 消息角色。 */
    private String role;

    /** 消息正文。 */
    private String content;

    /** 与真实消息正文绑定的有序生成过程；用户消息当前固定为空列表。 */
    private List<ThinkingProcessItem> thinkingProcess = List.of();

    /** 消息在会话内的单调递增序号。 */
    private Long conversationMessageSequence;

    /** 用户消息作者标识。 */
    private String authorUserId;

    /** assistant 消息的工具调用文档。 */
    private String toolCalls;

    /** tool observation 对应的工具执行标识。 */
    private String toolExecutionId;

    /** provider 生成的工具调用标识。 */
    private String toolCallId;

    /** observation 对应的工具名称。 */
    private String toolName;

    /** 消息投影范围。 */
    private String visibility;

    /** 消息乐观锁版本。 */
    private Long version;

    /** 用户可见的消息创建时间。 */
    private LocalDateTime createdAt;

    /** 组装用户提交消息。 */
    public static ConversationMessage user(
            String messageId,
            String sessionId,
            String content,
            long sequence,
            String authorUserId,
            String role) {
        ConversationMessage message = base(messageId, sessionId, role, content, sequence);
        message.authorUserId = authorUserId;
        return message;
    }

    /** 组装与执行绑定的唯一 assistant 中心占位消息。 */
    public static ConversationMessage assistantPlaceholder(
            String messageId, String sessionId, String executionId, long sequence, String role) {
        ConversationMessage message = base(messageId, sessionId, role, "", sequence);
        message.executionId = executionId;
        message.thinkingProcess = List.of();
        return message;
    }

    /** 组装与执行关联的 assistant 消息。 */
    public static ConversationMessage assistant(
            String messageId,
            String sessionId,
            String executionId,
            String content,
            long sequence,
            String role,
            String visibility) {
        ConversationMessage message = base(messageId, sessionId, role, content, sequence);
        message.executionId = executionId;
        message.visibility = visibility;
        return message;
    }

    /** 组装工具 observation 消息。 */
    public static ConversationMessage observation(
            String messageId,
            String sessionId,
            String executionId,
            String content,
            long sequence,
            String role,
            String visibility,
            String toolExecutionId,
            String toolCallId,
            String toolName) {
        ConversationMessage message =
                assistant(messageId, sessionId, executionId, content, sequence, role, visibility);
        message.toolExecutionId = toolExecutionId;
        message.toolCallId = toolCallId;
        message.toolName = toolName;
        return message;
    }

    /** 将已持久化的用户消息关联到其触发的执行。 */
    public void associateExecution(String executionId) {
        this.executionId = executionId;
    }

    /** 补充 assistant 消息的工具调用文档。 */
    public void attachToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    /** 使用不可变副本替换过程列表，避免持久化后被调用方继续修改。 */
    public void setThinkingProcess(List<ThinkingProcessItem> thinkingProcess) {
        this.thinkingProcess = thinkingProcess == null ? List.of() : List.copyOf(thinkingProcess);
    }

    private static ConversationMessage base(
            String messageId, String sessionId, String role, String content, long sequence) {
        ConversationMessage message = new ConversationMessage();
        message.conversationMessageId = messageId;
        message.conversationSessionId = sessionId;
        message.role = role;
        message.content = content;
        message.conversationMessageSequence = sequence;
        message.version = 0L;
        return message;
    }
}
