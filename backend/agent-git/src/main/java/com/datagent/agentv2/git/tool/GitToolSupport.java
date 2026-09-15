package com.datagent.agentv2.git.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitSessionResolver;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Git 工具共同边界：只接受结构化参数，可信执行解析会话，错误不携带 Git stderr。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public final class GitToolSupport {
    @Getter(AccessLevel.PACKAGE)
    private final GitWorkspaceService workspaces;

    @Getter(AccessLevel.PACKAGE)
    private final GitChangeService changes;

    private final GitSessionResolver sessions;
    private final GitProperties properties;
    private final ObjectMapper json;

    JsonNode schema(String source) {
        return json.readTree(source);
    }

    JsonNode result(Object value) {
        return json.valueToTree(value);
    }

    JsonNode call(
            JsonNode args,
            String schema,
            InvocationContext context,
            Function<String, Object> action)
            throws ToolBusinessException {
        validate(args, json.readTree(schema));
        if (context == null || context.executionId() == null || context.executionId().isBlank())
            throw new IllegalArgumentException("缺少可信执行上下文");
        try {
            return result(action.apply(sessions.resolveSessionId(context.executionId())));
        } catch (GitException failure) {
            throw new ToolBusinessException(failure.code(), "Git 操作未完成，请根据错误码检查草稿或服务端配置");
        }
    }

    /** 候选冻结包含每个变更文件的多条 Git 查询；工具预算不能先于合法子进程预算耗尽。 */
    Duration timeout() {
        return properties
                .commandTimeout()
                .multipliedBy(32L + 8L * properties.maxCandidateFiles())
                .plusSeconds(30);
    }

    int maxOperations() {
        return properties.maxFilesPerOperation();
    }

    long maxFileBytes() {
        return properties.maxFileBytes();
    }

    static String text(JsonNode a, String key, String fallback) {
        return a.has(key) ? a.path(key).asString() : fallback;
    }

    static int number(JsonNode a, String key, int fallback) {
        return a.has(key) ? a.path(key).asInt() : fallback;
    }

    static String path(JsonNode a, String key) {
        String value = text(a, key, "").replace('\\', '/');
        if (value.startsWith("/")
                || value.contains(":")
                || Arrays.stream(value.split("/"))
                        .anyMatch(s -> s.equals("..") || s.equalsIgnoreCase(".git")))
            throw new IllegalArgumentException("仅允许工作区相对路径");
        return value;
    }

    /** 除行数外限制字符数，防止单个超长行突破模型上下文预算；摘要始终来自完整文件。 */
    static TextWindow window(String content, int startLine, int lineCount) {
        String[] lines = content.split("\\R", -1);
        int start = Math.min(startLine - 1, lines.length);
        int end = Math.min(start + lineCount, lines.length);
        String selected = String.join("\n", Arrays.copyOfRange(lines, start, end));
        boolean truncated = start > 0 || end < lines.length || selected.length() > 20000;
        return new TextWindow(
                selected.substring(0, Math.min(20000, selected.length())),
                startLine,
                lines.length,
                truncated);
    }

    static <T> Page<T> page(List<T> items, int offset, int limit) {
        int start = Math.min(offset, items.size());
        int end = Math.min(start + limit, items.size());
        return new Page<>(items.subList(start, end), items.size(), end < items.size() ? end : null);
    }

    record Page<T>(List<T> items, int total, Integer nextOffset) {}

    record TextWindow(String content, int startLine, int totalLines, boolean truncated) {}

    /** 模型 Schema 不能代替运行时校验，尤其是未知字段与整数溢出。 */
    private static void validate(JsonNode value, JsonNode schema) {
        String type = schema.path("type").asString();
        boolean valid =
                switch (type) {
                    case "object" -> value != null && value.isObject();
                    case "array" -> value != null && value.isArray();
                    case "integer" ->
                            value != null && value.isIntegralNumber() && value.canConvertToLong();
                    case "boolean" -> value != null && value.isBoolean();
                    case "string" -> value != null && value.isTextual();
                    default -> false;
                };
        if (!valid) throw new IllegalArgumentException("Git 参数类型无效");
        if (type.equals("object")) {
            for (String name : value.propertyNames()) {
                if (!schema.path("properties").has(name))
                    throw new IllegalArgumentException("未知 Git 参数: " + name);
                validate(value.path(name), schema.path("properties").path(name));
            }
            for (JsonNode name : schema.path("required"))
                if (!value.has(name.asString()))
                    throw new IllegalArgumentException("缺少 Git 参数: " + name.asString());
        } else if (type.equals("integer")) {
            long n = value.asLong();
            if (n < schema.path("minimum").asLong(0)
                    || n > schema.path("maximum").asLong(Long.MAX_VALUE))
                throw new IllegalArgumentException("Git 整数参数越界");
        } else if (type.equals("string")) {
            int n = value.asString().length();
            if (n < schema.path("minLength").asInt(0)
                    || n > schema.path("maxLength").asInt(Integer.MAX_VALUE))
                throw new IllegalArgumentException("Git 文本参数长度越界");
            if (schema.has("enum")) {
                boolean found = false;
                for (JsonNode allowed : schema.path("enum"))
                    if (allowed.equals(value)) found = true;
                if (!found) throw new IllegalArgumentException("Git 操作类型无效");
            }
        } else if (type.equals("array")) {
            if (value.size() < schema.path("minItems").asInt(0)
                    || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE))
                throw new IllegalArgumentException("Git 操作数量越界");
            for (JsonNode item : value) validate(item, schema.path("items"));
        }
    }
}
