package com.datagent.agentv2.persistence.json;

import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ThinkingProcessType;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 强类型 ProcessItem 列表与 conversation_message JSONB 文档之间的唯一编解码边界。 */
@Component
public final class ConversationProcessDocumentCodec {

    private static final String EMPTY_DOCUMENT = "[]";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final ObjectMapper json;

    /** 使用确定性的 Jackson 配置创建持久化 codec。 */
    public ConversationProcessDocumentCodec() {
        this(JsonMapper.builder().build());
    }

    ConversationProcessDocumentCodec(ObjectMapper json) {
        this.json = json;
    }

    /** 将有序强类型节点序列化为 JSONB 数组字符串。 */
    public String write(List<ThinkingProcessItem> items) {
        List<ThinkingProcessItem> safeItems = items == null ? List.of() : List.copyOf(items);
        validateOrder(safeItems);
        ArrayNode document = json.createArrayNode();
        safeItems.forEach(item -> document.add(writeItem(item)));
        return json.writeValueAsString(document);
    }

    /** 从 JSONB 数组读取并严格校验强类型节点。 */
    public List<ThinkingProcessItem> read(String document) {
        String source = document == null || document.isBlank() ? EMPTY_DOCUMENT : document;
        try {
            JsonNode root = json.readTree(source);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("thinkingProcess root must be an array");
            }
            List<ThinkingProcessItem> items = new ArrayList<>();
            root.forEach(node -> items.add(readItem(node)));
            validateOrder(items);
            return List.copyOf(items);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("invalid thinkingProcess document", failure);
        }
    }

    /** 按节点类型写入稳定的 camelCase JSON 文档。 */
    private ObjectNode writeItem(ThinkingProcessItem item) {
        ObjectNode node = json.createObjectNode();
        writeCommon(node, item);
        if (item instanceof ModelThinkingProcessItem thinking) {
            node.put("content", thinking.content());
            node.put("contentTruncated", thinking.contentTruncated());
            node.put("originalContentSizeBytes", thinking.originalContentSizeBytes());
            node.put("contentSha256", thinking.contentSha256());
            node.put("modelInvocationSequence", thinking.modelInvocationSequence());
            node.put("modelProvider", thinking.modelProvider());
            node.put("modelName", thinking.modelName());
            return node;
        }
        ToolThinkingProcessItem tool = (ToolThinkingProcessItem) item;
        node.put("toolId", tool.toolId());
        node.put("toolName", tool.toolName());
        node.put("toolExecutionId", tool.toolExecutionId());
        node.put("toolCallId", tool.toolCallId());
        node.put("modelInvocationSequence", tool.modelInvocationSequence());
        node.set("input", writePayload(tool.input()));
        writeNullableNode(
                node, "output", tool.output() == null ? null : writePayload(tool.output()));
        writeNullableBoolean(node, "success", tool.success());
        writeNullableText(node, "errorCode", tool.errorCode());
        writeNullableText(node, "errorMessage", tool.errorMessage());
        return node;
    }

    /** 写入所有 ProcessItem 共用的生命周期字段。 */
    private static void writeCommon(ObjectNode node, ThinkingProcessItem item) {
        node.put("processItemId", item.processItemId().toString());
        node.put("sequence", item.sequence());
        node.put("type", item.type().name());
        node.put("status", item.status().name());
        node.put("createdAt", item.createdAt().toString());
        writeNullableText(node, "startedAt", text(item.startedAt()));
        writeNullableText(node, "completedAt", text(item.completedAt()));
        if (item.durationMillis() == null) {
            node.putNull("durationMillis");
        } else {
            node.put("durationMillis", item.durationMillis());
        }
        node.put("schemaVersion", item.schemaVersion());
    }

    /** 根据显式 type 字段构造具体 Java record。 */
    private ThinkingProcessItem readItem(JsonNode node) {
        int schemaVersion = requiredInt(node, "schemaVersion");
        if (schemaVersion != ThinkingProcessItem.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported schemaVersion: " + schemaVersion);
        }
        ThinkingProcessType type;
        try {
            type = ThinkingProcessType.valueOf(requiredText(node, "type"));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("unsupported process item type", failure);
        }
        try {
            return switch (type) {
                case THINKING -> readThinking(node, schemaVersion);
                case TOOL -> readTool(node, schemaVersion);
            };
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("invalid " + type + " process item", failure);
        }
    }

    private ModelThinkingProcessItem readThinking(JsonNode node, int schemaVersion) {
        CommonFields common = readCommon(node, schemaVersion);
        return new ModelThinkingProcessItem(
                common.processItemId(),
                common.sequence(),
                common.status(),
                common.createdAt(),
                common.startedAt(),
                common.completedAt(),
                common.durationMillis(),
                common.schemaVersion(),
                requiredText(node, "content"),
                requiredBoolean(node, "contentTruncated"),
                requiredLong(node, "originalContentSizeBytes"),
                requiredText(node, "contentSha256"),
                requiredLong(node, "modelInvocationSequence"),
                requiredText(node, "modelProvider"),
                requiredText(node, "modelName"));
    }

    private ToolThinkingProcessItem readTool(JsonNode node, int schemaVersion) {
        CommonFields common = readCommon(node, schemaVersion);
        return new ToolThinkingProcessItem(
                common.processItemId(),
                common.sequence(),
                common.status(),
                common.createdAt(),
                common.startedAt(),
                common.completedAt(),
                common.durationMillis(),
                common.schemaVersion(),
                requiredText(node, "toolId"),
                requiredText(node, "toolName"),
                requiredText(node, "toolExecutionId"),
                requiredText(node, "toolCallId"),
                requiredLong(node, "modelInvocationSequence"),
                readPayload(requiredNode(node, "input")),
                nullableNode(node, "output") == null
                        ? null
                        : readPayload(nullableNode(node, "output")),
                nullableBoolean(node, "success"),
                nullableText(node, "errorCode"),
                nullableText(node, "errorMessage"));
    }

    private CommonFields readCommon(JsonNode node, int schemaVersion) {
        return new CommonFields(
                UUID.fromString(requiredText(node, "processItemId")),
                requiredLong(node, "sequence"),
                ThinkingProcessStatus.valueOf(requiredText(node, "status")),
                Instant.parse(requiredText(node, "createdAt")),
                nullableInstant(node, "startedAt"),
                nullableInstant(node, "completedAt"),
                nullableLong(node, "durationMillis"),
                schemaVersion);
    }

    /** 将有界载荷读取为普通 Java JSON tree，避免领域模块依赖 Jackson。 */
    private BoundedProcessPayload readPayload(JsonNode node) {
        JsonNode value = nullableNode(node, "value");
        return new BoundedProcessPayload(
                value == null ? null : json.convertValue(value, Object.class),
                nullableText(node, "preview"),
                requiredBoolean(node, "truncated"),
                requiredLong(node, "originalSizeBytes"),
                requiredText(node, "sha256"),
                requiredText(node, "contentType"));
    }

    private ObjectNode writePayload(BoundedProcessPayload payload) {
        ObjectNode node = json.createObjectNode();
        writeNullableNode(
                node, "value", payload.value() == null ? null : json.valueToTree(payload.value()));
        writeNullableText(node, "preview", payload.preview());
        node.put("truncated", payload.truncated());
        node.put("originalSizeBytes", payload.originalSizeBytes());
        node.put("sha256", payload.sha256());
        node.put("contentType", payload.contentType());
        return node;
    }

    /** 列表必须使用唯一 UUID 和严格递增且唯一的消息内序号。 */
    private static void validateOrder(List<ThinkingProcessItem> items) {
        Set<UUID> ids = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        long previous = 0L;
        for (ThinkingProcessItem item : items) {
            if (!ids.add(item.processItemId()) || !sequences.add(item.sequence())) {
                throw new IllegalStateException("duplicate process item identity or sequence");
            }
            if (item.sequence() <= previous) {
                throw new IllegalStateException("process item sequence must be strictly ordered");
            }
            previous = item.sequence();
        }
    }

    private static JsonNode requiredNode(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("missing field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        return requiredNode(node, field).asString();
    }

    private static long requiredLong(JsonNode node, String field) {
        return requiredNode(node, field).asLong();
    }

    private static int requiredInt(JsonNode node, String field) {
        return requiredNode(node, field).asInt();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        return requiredNode(node, field).asBoolean();
    }

    private static JsonNode nullableNode(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = nullableNode(node, field);
        return value == null ? null : value.asString();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = nullableNode(node, field);
        return value == null ? null : value.asLong();
    }

    private static Boolean nullableBoolean(JsonNode node, String field) {
        JsonNode value = nullableNode(node, field);
        return value == null ? null : value.asBoolean();
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : Instant.parse(value);
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private static void writeNullableText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void writeNullableBoolean(ObjectNode node, String field, Boolean value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void writeNullableNode(ObjectNode node, String field, JsonNode value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.set(field, value);
        }
    }

    private record CommonFields(
            UUID processItemId,
            long sequence,
            ThinkingProcessStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Long durationMillis,
            int schemaVersion) {}
}
