package com.datagent.agentv2.conversation.react;

import java.util.List;
import java.util.Objects;

/** 一条不可变模型输入消息；toolCalls 与 tool 元数据互斥。 */
public record ModelInputMessage(
        String role,
        String content,
        List<ToolCallDocument> toolCalls,
        String toolCallId,
        String toolName) {

    public ModelInputMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(toolCalls);
    }

    public static ModelInputMessage plain(String role, String content) {
        return new ModelInputMessage(role, content, List.of(), null, null);
    }

    public static ModelInputMessage assistantToolCalls(List<ToolCallDocument> toolCalls) {
        return new ModelInputMessage("assistant", "", toolCalls, null, null);
    }

    public static ModelInputMessage tool(String content, String toolCallId, String toolName) {
        return new ModelInputMessage("tool", content, List.of(), toolCallId, toolName);
    }
}
