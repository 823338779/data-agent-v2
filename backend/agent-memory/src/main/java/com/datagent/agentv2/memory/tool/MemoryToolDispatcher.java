package com.datagent.agentv2.memory.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.memory.MemoryCommand;
import com.datagent.agentv2.memory.MemoryEntry;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryOutcome;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemoryService;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.MemorySource;
import com.datagent.agentv2.memory.MemoryWriteContext;
import com.datagent.agentv2.memory.MemoryWriteReason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 严格校验模型参数并把有限动作映射到统一记忆服务；不接受任何身份或执行模式参数。 */
@Component
@RequiredArgsConstructor
public final class MemoryToolDispatcher {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private static final Set<String> LIST_FIELDS = Set.of("action", "kind", "cursor", "limit");
    private static final Set<String> ADD_FIELDS =
            Set.of("action", "kind", "content", "write_reason", "evidence_message_ids", "restore");
    private static final Set<String> REPLACE_FIELDS =
            Set.of(
                    "action",
                    "memory_id",
                    "expected_version",
                    "new_content",
                    "write_reason",
                    "evidence_message_ids");
    private static final Set<String> REMOVE_FIELDS =
            Set.of(
                    "action",
                    "memory_id",
                    "expected_version",
                    "write_reason",
                    "evidence_message_ids");
    private static final Set<String> CONFIRM_FIELDS =
            Set.of("action", "memory_id", "expected_version", "evidence_message_ids");

    private final MemoryService service;
    private final ObjectMapper json;

    public MemoryOutcome dispatch(MemoryWriteContext context, JsonNode arguments) {
        return service.execute(context, parse(context, arguments));
    }

    /** 复用完整参数校验；后台在查历史成功前也必须解析，不能用非法附加字段伪装历史成功。 */
    public MemoryCommand parse(MemoryWriteContext context, JsonNode arguments) {
        requireObject(arguments);
        String action = requiredText(arguments, "action");
        MemoryCommand command =
                switch (action) {
                    case "add" -> add(context, arguments);
                    case "replace" -> replace(context, arguments);
                    case "remove" -> remove(context, arguments);
                    case "confirm" -> confirm(context, arguments);
                    case "list" -> throw invalid("list is a read-only action");
                    default -> throw invalid("Unsupported memory action");
                };
        return command;
    }

    /** 读取当前一致快照并分页输出模型需要的字段，禁止泄露规范化摘要等内部存储字段。 */
    public JsonNode list(JsonNode arguments) throws ToolBusinessException {
        requireObject(arguments);
        if (!"list".equals(requiredText(arguments, "action"))) {
            throw invalid("Expected list action");
        }
        requireOnly(arguments, LIST_FIELDS);
        MemoryKind kind = optionalKind(arguments.path("kind"));
        String cursor = optionalText(arguments.path("cursor"), "cursor");
        int limit = optionalLimit(arguments.path("limit"));
        MemorySnapshot snapshot = service.snapshot();
        if (!snapshot.state().readEnabled()) {
            throw new ToolBusinessException(
                    "INVALID_REQUEST",
                    "个人记忆读取已关闭",
                    json.createObjectNode().put("status", "INVALID_REQUEST"));
        }
        List<MemoryEntry> candidates =
                snapshot.entries().stream()
                        .filter(item -> kind == null || item.kind() == kind)
                        .filter(item -> cursor == null || item.memoryId().compareTo(cursor) > 0)
                        .sorted(Comparator.comparing(MemoryEntry::memoryId))
                        .toList();
        List<MemoryListItem> items =
                candidates.stream().limit(limit).map(MemoryListItem::composeFromEntry).toList();
        String nextCursor =
                candidates.size() > items.size() && !items.isEmpty()
                        ? items.getLast().memoryId()
                        : null;
        return json.valueToTree(
                new MemoryListResult(
                        snapshot.state().revision(), items, snapshot.quotas(), nextCursor));
    }

    /** 在进入服务事务前提取证据 ID，供 Registry 校验本轮冻结允许集合。 */
    public List<String> evidenceIds(JsonNode arguments) {
        requireObject(arguments);
        return requiredTextList(arguments, "evidence_message_ids");
    }

    private MemoryCommand add(MemoryWriteContext context, JsonNode arguments) {
        requireOnly(arguments, ADD_FIELDS);
        boolean restore = optionalBoolean(arguments.path("restore"), "restore", false);
        if (context.caller() == MemoryWriteContext.Caller.BACKGROUND && restore) {
            throw invalid("Background memory cannot restore a suppression");
        }
        return new MemoryCommand.Add(
                MemoryKind.parse(requiredText(arguments, "kind")),
                requiredText(arguments, "content"),
                reason(context, arguments.path("write_reason")),
                requiredTextList(arguments, "evidence_message_ids"),
                restore);
    }

    private MemoryCommand replace(MemoryWriteContext context, JsonNode arguments) {
        requireOnly(arguments, REPLACE_FIELDS);
        return new MemoryCommand.Replace(
                requiredText(arguments, "memory_id"),
                requiredPositiveLong(arguments, "expected_version"),
                requiredText(arguments, "new_content"),
                reason(context, arguments.path("write_reason")),
                requiredTextList(arguments, "evidence_message_ids"));
    }

    private MemoryCommand remove(MemoryWriteContext context, JsonNode arguments) {
        requireOnly(arguments, REMOVE_FIELDS);
        return new MemoryCommand.Remove(
                requiredText(arguments, "memory_id"),
                requiredPositiveLong(arguments, "expected_version"),
                reason(context, arguments.path("write_reason")),
                requiredTextList(arguments, "evidence_message_ids"));
    }

    private MemoryCommand confirm(MemoryWriteContext context, JsonNode arguments) {
        if (context.caller() != MemoryWriteContext.Caller.BACKGROUND) {
            throw invalid("confirm is private to background review");
        }
        requireOnly(arguments, CONFIRM_FIELDS);
        return new MemoryCommand.Confirm(
                requiredText(arguments, "memory_id"),
                requiredPositiveLong(arguments, "expected_version"),
                requiredTextList(arguments, "evidence_message_ids"));
    }

    private static MemoryWriteReason reason(MemoryWriteContext context, JsonNode node) {
        if (context.caller() == MemoryWriteContext.Caller.BACKGROUND) {
            if (!node.isMissingNode()) {
                throw invalid("Background reason is server controlled");
            }
            return MemoryWriteReason.AGENT_INFERRED;
        }
        if (!node.isTextual()) {
            throw invalid("write_reason must be a string");
        }
        return switch (node.asString()) {
            case "user_requested" -> MemoryWriteReason.USER_REQUESTED;
            case "agent_inferred" -> MemoryWriteReason.AGENT_INFERRED;
            default -> throw invalid("write_reason is invalid");
        };
    }

    private static void requireObject(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw invalid("Memory arguments must be an object");
        }
    }

    private static void requireOnly(JsonNode arguments, Set<String> allowed) {
        for (String name : arguments.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid("Unexpected memory argument: " + name);
            }
        }
    }

    private static String requiredText(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        if (!node.isTextual() || node.asString().isBlank()) {
            throw invalid(name + " must be a non-blank string");
        }
        return node.asString();
    }

    private static String optionalText(JsonNode node, String name) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asString().isBlank()) {
            throw invalid(name + " must be a non-blank string");
        }
        return node.asString();
    }

    private static MemoryKind optionalKind(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid("kind must be a string");
        }
        try {
            return MemoryKind.parse(node.asString());
        } catch (IllegalArgumentException failure) {
            throw invalid("kind is invalid");
        }
    }

    private static long requiredPositiveLong(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 1) {
            throw invalid(name + " must be a positive integer");
        }
        return node.asLong();
    }

    private static int optionalLimit(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return DEFAULT_LIMIT;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid("limit must be an integer");
        }
        int limit = node.asInt();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit must be between 1 and 100");
        }
        return limit;
    }

    private static boolean optionalBoolean(JsonNode node, String name, boolean fallback) {
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw invalid(name + " must be a boolean");
        }
        return node.asBoolean();
    }

    private static List<String> requiredTextList(JsonNode arguments, String name) {
        JsonNode node = arguments.path(name);
        if (!node.isArray() || node.isEmpty()) {
            throw invalid(name + " must be a non-empty array");
        }
        var result = new ArrayList<String>();
        var unique = new HashSet<String>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asString().isBlank() || !unique.add(item.asString())) {
                throw invalid(name + " must contain unique non-blank strings");
            }
            result.add(item.asString());
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** list 的公开条目视图，刻意排除 normalizedHash、内部版本与提示词版本。 */
    public record MemoryListItem(
            String memoryId,
            long version,
            String content,
            MemoryKind kind,
            MemorySource source,
            boolean userProtected) {
        private static MemoryListItem composeFromEntry(MemoryEntry entry) {
            return new MemoryListItem(
                    entry.memoryId(),
                    entry.version(),
                    entry.content(),
                    entry.kind(),
                    entry.source(),
                    entry.userProtected());
        }
    }

    /** list 返回当前一致快照 revision、完整分类用量和稳定 ID 游标，不改变本轮冻结写入资格。 */
    public record MemoryListResult(
            long revision,
            List<MemoryListItem> items,
            Map<MemoryKind, MemoryQuota> quotas,
            String nextCursor) {
        public MemoryListResult {
            items = List.copyOf(items);
            quotas = Map.copyOf(quotas);
        }
    }
}
