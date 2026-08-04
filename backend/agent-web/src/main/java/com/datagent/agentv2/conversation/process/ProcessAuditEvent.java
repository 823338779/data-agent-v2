package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
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
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("processItem", item);
        document.put("fullThinkingContent", fullThinkingContent);
        document.put("fullToolInput", fullToolInput);
        document.put("fullToolOutput", fullToolOutput);
        return document;
    }
}
