package com.datagent.agentv2.conversation.react;

import java.util.List;
import java.util.Objects;

/** 一条不可变模型输入消息；模型协议角色与数据库中的真实消息角色彼此独立。 */
public record ModelInputMessage(
        Role role,
        String content,
        List<ModelToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    public ModelInputMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(toolCalls);
    }

    public static ModelInputMessage plain(String role, String content) {
        return new ModelInputMessage(Role.fromValue(role), content, List.of(), null, null);
    }

    public static ModelInputMessage assistantToolCalls(List<ModelToolCall> toolCalls) {
        return new ModelInputMessage(Role.ASSISTANT, "", toolCalls, null, null);
    }

    public static ModelInputMessage tool(String content, String toolCallId, String toolName) {
        return new ModelInputMessage(Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    /** 模型供应商协议允许 TOOL；该角色不会持久化为 conversation_message。 */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** 将真实消息角色转换为模型协议角色。 */
        public static Role fromValue(String value) {
            for (Role candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown model input role: " + value);
        }
    }
}
