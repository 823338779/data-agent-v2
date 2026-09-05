package com.datagent.agentv2.persistence.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ArtifactSpec 与 UI Block payload 的唯一 JSONB 编解码边界。 */
@Component
public final class ArtifactJsonDocumentCodec {

    private final JsonMapper json = JsonMapper.builder().build();

    /** 把已校验的对象转换为规范 JSON 文本。 */
    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    /** 从数据库 JSONB 文本恢复只读 JSON 树。 */
    public Object read(String document) {
        if (document == null) {
            return null;
        }
        JsonNode node = json.readTree(document);
        if (!node.isObject()) {
            throw new IllegalStateException("artifact JSON document must be an object");
        }
        return node;
    }
}
