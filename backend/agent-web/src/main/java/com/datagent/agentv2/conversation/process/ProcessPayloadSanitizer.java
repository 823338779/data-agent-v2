package com.datagent.agentv2.conversation.process;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 在计算大小、哈希、持久化和审计前递归清除工具载荷中的敏感字段。 */
@Component
@RequiredArgsConstructor
public final class ProcessPayloadSanitizer {

    public static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS =
            Set.of(
                    "password",
                    "passwd",
                    "secret",
                    "token",
                    "accesstoken",
                    "refreshtoken",
                    "authorization",
                    "cookie",
                    "setcookie",
                    "connectionstring",
                    "jdbcurl");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");
    private static final Pattern TEXT_SECRET =
            Pattern.compile(
                    "(?i)([\\\"']?(?:password|passwd|secret|token|access[_-]?token|refresh[_-]?token|authorization|cookie|set[_-]?cookie|connection[_-]?string|jdbc[_-]?url)[\\\"']?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|[^,;\\r\\n&}]+)");

    private final ObjectMapper json;

    /** 返回与输入结构等价但敏感值已替换的新 JSON tree。 */
    public JsonNode sanitizeJson(JsonNode source) {
        if (source == null || source.isNull()) {
            return json.nullNode();
        }
        if (source.isObject()) {
            ObjectNode sanitized = json.createObjectNode();
            source.forEachEntry(
                    (key, value) ->
                            sanitized.set(
                                    key,
                                    isSensitive(key)
                                            ? json.getNodeFactory().textNode(REDACTED_VALUE)
                                            : sanitizeJson(value)));
            return sanitized;
        }
        if (source.isArray()) {
            ArrayNode sanitized = json.createArrayNode();
            source.forEach(value -> sanitized.add(sanitizeJson(value)));
            return sanitized;
        }
        return source.deepCopy();
    }

    /** 清理日志和 thinking 文本中的常见 key/value 敏感片段。 */
    public String sanitizeText(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = TEXT_SECRET.matcher(source);
        StringBuilder sanitized = new StringBuilder(source.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    sanitized, Matcher.quoteReplacement(matcher.group(1) + REDACTED_VALUE));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private static boolean isSensitive(String key) {
        String normalized =
                NON_ALPHANUMERIC
                        .matcher(key == null ? "" : key)
                        .replaceAll("")
                        .toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized);
    }
}
