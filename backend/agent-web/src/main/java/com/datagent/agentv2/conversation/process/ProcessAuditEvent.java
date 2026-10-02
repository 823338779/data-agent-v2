package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.memory.MemoryStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 事务提交后输出的 ProcessItem 安全完整载荷；数据库节点仍使用有界表示。 */
public record ProcessAuditEvent(
        ThinkingProcessItem item,
        String fullThinkingContent,
        JsonNode fullToolInput,
        JsonNode fullToolOutput) {

    public ProcessAuditEvent {
        Objects.requireNonNull(item, "item");
    }

    /** 按固定字段顺序组装独立审计文档，便于按 ProcessItem UUID 关联排查。 */
    public Map<String, Object> safeFullDocument(ObjectMapper json) {
        Objects.requireNonNull(json, "json");
        // 个人记忆日志不能复制工具正文/证据或截断预览；仅改变日志投影，原过程对象仍供数据库与界面使用。
        if (item instanceof ToolThinkingProcessItem tool && "memory".equals(tool.toolName())) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("processItemId", tool.processItemId());
            safe.put("sequence", tool.sequence());
            safe.put("toolCallId", tool.toolCallId());
            safe.put("toolName", tool.toolName());
            safe.put("status", tool.status());
            safe.put("createdAt", tool.createdAt());
            safe.put("startedAt", tool.startedAt());
            safe.put("completedAt", tool.completedAt());
            safe.put("durationMillis", tool.durationMillis());
            safe.put("errorCode", memoryStatus(tool.errorCode()));
            safe.put(
                    "memoryStatus",
                    memoryStatus(
                            fullToolOutput == null
                                    ? null
                                    : fullToolOutput.path("status").asString(null)));
            return safe;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("processItem", item);
        document.put("fullThinkingContent", fullThinkingContent);
        document.put("fullToolInput", fullToolInput);
        document.put("fullToolOutput", fullToolOutput);
        return document;
    }

    /** 只允许服务端已定义的有限码，错误字符串不可借此把个人信息写入日志。 */
    private static MemoryStatus memoryStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MemoryStatus.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
