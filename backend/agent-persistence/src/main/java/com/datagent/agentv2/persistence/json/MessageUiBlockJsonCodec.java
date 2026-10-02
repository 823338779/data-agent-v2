package com.datagent.agentv2.persistence.json;

import org.mapstruct.Named;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 消息 UI Block payload 的 JSONB 编解码边界。 */
@Component
public final class MessageUiBlockJsonCodec {

    private final JsonMapper json = JsonMapper.builder().build();

    /** 显式限定 JSON 转换，避免 MapStruct 将普通 ID、类型和变体字符串再次编码。 */
    @Named("writeUiBlockPayload")
    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    /** 从数据库 JSONB 文本恢复只读 JSON 树。 */
    @Named("readUiBlockPayload")
    public Object read(String document) {
        if (document == null) {
            return null;
        }
        JsonNode node = json.readTree(document);
        if (!node.isObject()) {
            throw new IllegalStateException("UI block JSON document must be an object");
        }
        return node;
    }
}
