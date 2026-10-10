package com.datagent.agentv2.development.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.development.application.DevelopmentWorkspaceService;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.diagnostics.SafeFailures;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Git 工具共同边界：只接受结构化参数，可信执行解析会话，错误不携带 Git stderr。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public final class DevelopmentToolSupport {
    private static final String TYPE = "type";
    private static final String OBJECT = "object";
    private static final String ARRAY = "array";
    private static final String INTEGER = "integer";
    private static final String BOOLEAN = "boolean";
    private static final String STRING = "string";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String MINIMUM = "minimum";
    private static final String MAXIMUM = "maximum";
    private static final String MIN_LENGTH = "minLength";
    private static final String MAX_LENGTH = "maxLength";
    private static final String MIN_ITEMS = "minItems";
    private static final String MAX_ITEMS = "maxItems";
    private static final String ITEMS = "items";
    private static final String ENUM = "enum";
    private static final String EMPTY = "";
    private static final String SLASH = "/";
    private static final String COLON = ":";
    private static final String PARENT = "..";
    private static final String GIT_DIRECTORY = ".git";
    private static final String LINE_BREAK_PATTERN = "\\R";
    private static final String NEWLINE = "\n";

    /** 固定会话的文件用例。 */
    @Getter(AccessLevel.PACKAGE)
    private final DevelopmentWorkspaceService workspaces;

    /** 可信执行归属。 */
    private final AnalysisSessionResolver sessions;

    /** 服务端文件和命令资源上限。 */
    private final GitProperties properties;

    /** 协议序列化器。 */
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
        if (context == null || context.executionId() == null || context.executionId().isBlank()) {
            throw new IllegalArgumentException("缺少可信执行上下文");
        }
        try {
            String session = sessions.resolveSession(context);
            return result(action.apply(session));
        } catch (DevelopmentException failure) {
            throw new ToolBusinessException(failure.getCode(), failure.getMessage());
        } catch (GitException failure) {
            // 错误消息可能含 Git stderr 或远端凭据；日志只保留类型、堆栈和可信执行标识。
            log.warn(
                    "Git tool failed executionId={} code={}",
                    context.executionId(),
                    failure.code(),
                    SafeFailures.sanitized(failure));
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
        String value = text(a, key, EMPTY).replace('\\', '/');
        if (value.startsWith(SLASH)
                || value.contains(COLON)
                || Arrays.stream(value.split(SLASH))
                        .anyMatch(s -> s.equals(PARENT) || s.equalsIgnoreCase(GIT_DIRECTORY))) {
            throw new IllegalArgumentException("仅允许工作区相对路径");
        }
        return value;
    }

    /** 除行数外限制字符数，防止单个超长行突破模型上下文预算；摘要始终来自完整文件。 */
    static TextWindow window(String content, int startLine, int lineCount) {
        String[] lines = content.split(LINE_BREAK_PATTERN, -1);
        int start = Math.min(startLine - 1, lines.length);
        int end = Math.min(start + lineCount, lines.length);
        String selected = String.join(NEWLINE, Arrays.copyOfRange(lines, start, end));
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

    record Page<T>(
            /** 当前页。 */
            List<T> items,
            /** 总条数。 */
            int total,
            /** 后续偏移，null表示结束。 */
            Integer nextOffset) {}

    record TextWindow(
            /** 有界正文。 */
            String content,
            /** 起始行，从1计。 */
            int startLine,
            /** 完整文件行数。 */
            int totalLines,
            /** 是否省略正文。 */
            boolean truncated) {}

    /** 模型 Schema 不能代替运行时校验，尤其是未知字段与整数溢出。 */
    public static void validate(JsonNode value, JsonNode schema) {
        String type = schema.path(TYPE).asString();
        boolean valid =
                switch (type) {
                    case OBJECT -> value != null && value.isObject();
                    case ARRAY -> value != null && value.isArray();
                    case INTEGER ->
                            value != null && value.isIntegralNumber() && value.canConvertToLong();
                    case BOOLEAN -> value != null && value.isBoolean();
                    case STRING -> value != null && value.isTextual();
                    default -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException("Git 参数类型无效");
        }
        if (type.equals(OBJECT)) {
            for (String name : value.propertyNames()) {
                if (!schema.path(PROPERTIES).has(name)) {
                    throw new IllegalArgumentException("未知 Git 参数: " + name);
                }
                validate(value.path(name), schema.path(PROPERTIES).path(name));
            }
            for (JsonNode name : schema.path(REQUIRED)) {
                if (!value.has(name.asString())) {
                    throw new IllegalArgumentException("缺少 Git 参数: " + name.asString());
                }
            }
        } else if (type.equals(INTEGER)) {
            long n = value.asLong();
            if (n < schema.path(MINIMUM).asLong(0)
                    || n > schema.path(MAXIMUM).asLong(Long.MAX_VALUE)) {
                throw new IllegalArgumentException("Git 整数参数越界");
            }
        } else if (type.equals(STRING)) {
            int n = value.asString().length();
            if (n < schema.path(MIN_LENGTH).asInt(0)
                    || n > schema.path(MAX_LENGTH).asInt(Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("Git 文本参数长度越界");
            }
            if (schema.has(ENUM)) {
                boolean found = false;
                for (JsonNode allowed : schema.path(ENUM)) {
                    if (allowed.equals(value)) {
                        found = true;
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("Git 操作类型无效");
                }
            }
        } else if (type.equals(ARRAY)) {
            if (value.size() < schema.path(MIN_ITEMS).asInt(0)
                    || value.size() > schema.path(MAX_ITEMS).asInt(Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("Git 操作数量越界");
            }
            for (JsonNode item : value) {
                validate(item, schema.path(ITEMS));
            }
        }
    }
}
