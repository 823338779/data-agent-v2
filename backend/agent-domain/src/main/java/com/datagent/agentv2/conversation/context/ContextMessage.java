package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/** 与供应商无关的模型输入消息，支持历史 thinking 与工具协议。 */
public record ContextMessage(
        Role role,
        String content,
        String thinking,
        List<ContextToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    public ContextMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(toolCalls);
    }

    public static ContextMessage system(String content) {
        return plain(Role.SYSTEM, content);
    }

    public static ContextMessage user(String content) {
        return plain(Role.USER, content);
    }

    public static ContextMessage assistant(String content, String thinking) {
        return new ContextMessage(Role.ASSISTANT, content, thinking, List.of(), null, null);
    }

    public static ContextMessage assistantToolCalls(List<ContextToolCall> toolCalls) {
        return new ContextMessage(Role.ASSISTANT, "", null, toolCalls, null, null);
    }

    public static ContextMessage tool(String content, String toolCallId, String toolName) {
        return new ContextMessage(Role.TOOL, content, null, List.of(), toolCallId, toolName);
    }

    private static ContextMessage plain(Role role, String content) {
        return new ContextMessage(role, content, null, List.of(), null, null);
    }

    /** 模型上下文允许的协议角色。 */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }
}
