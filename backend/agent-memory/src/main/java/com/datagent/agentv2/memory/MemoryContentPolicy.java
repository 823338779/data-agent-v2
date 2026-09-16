package com.datagent.agentv2.memory;

import com.datagent.agentv2.memory.config.MemoryProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * 个人记忆正文的规范化、稳定指纹和有限安全校验。
 *
 * <p>规范化只服务于精确去重，持久化仍保留原文；安全扫描是纵深防御，不能替代模型侧提示注入隔离。
 */
@RequiredArgsConstructor
public final class MemoryContentPolicy {
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,!?;:。，！？；：]+$");
    private static final Pattern ALLOWED_PUNCTUATION_OR_WHITESPACE =
            Pattern.compile("(?U)[\\s.,!?;:。，！？；：]+");
    private static final Pattern SECRET =
            Pattern.compile(
                    "(?iu)(?:api[_-]?key|secret|access[_-]?token|authorization)\\s*[:=]\\s*\\S+"
                            + "|(?:^|\\s)bearer\\s+\\S+|(?:^|\\s)sk-[a-z0-9_-]{16,}"
                            + "|(?:^|\\s)AKIA[A-Z0-9]{16}(?:\\s|$)");
    private static final Pattern ROLE_MARKER =
            Pattern.compile(
                    "(?imu)(?:^|\\R)\\s*(?:system|developer|assistant|tool)\\s*[:：]"
                            + "|<\\|(?:system|developer|assistant|tool)\\|>");
    private static final Pattern RUNTIME_MARKER =
            Pattern.compile(
                    "(?iu)</?(?:runtime[_-]?(?:context|memory)|memory[_-]?context)>"
                            + "|\\[/?(?:runtime[_-]?(?:context|memory)|memory[_-]?context)\\]"
                            + "|(?:begin|end)[_-](?:runtime|memory)[_-]context");

    private final MemoryProperties properties;

    /** 版本 1：小写、折叠 Unicode 空白、去首尾空白和末尾连续标点。 */
    public String normalize(String input) {
        if (input == null) {
            throw invalid();
        }
        String normalized =
                TRAILING_PUNCTUATION
                        .matcher(input.toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").trim())
                        .replaceAll("");
        if (normalized.isBlank()) {
            throw invalid();
        }
        return normalized;
    }

    /** 对规范化后的 UTF-8 正文计算小写十六进制 SHA-256。 */
    public String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(normalize(input).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /** 校验正文安全边界和单条 UTF-16 上限；异常始终只返回安全消息。 */
    public void validate(MemoryKind kind, String content) {
        Objects.requireNonNull(kind, "kind");
        validateContent(content);
        if (content.length() > limit(kind)) {
            throw new MemoryException(MemoryStatus.QUOTA_EXCEEDED, "记忆超过分类容量限制");
        }
    }

    /** 独立检查内容有效性和安全性，允许统一写入边界先去重、再检查最终库存容量。 */
    public void validateContent(String content) {
        normalize(content);
        // 规范化 v1 只移除末尾连续标点；这里单独拒绝散布空白的纯标点，避免改变既有指纹。
        if (ALLOWED_PUNCTUATION_OR_WHITESPACE.matcher(content).replaceAll("").isBlank()) {
            throw invalid();
        }
        if (hasDangerousControl(content)
                || SECRET.matcher(content).find()
                || ROLE_MARKER.matcher(content).find()
                || RUNTIME_MARKER.matcher(content).find()) {
            throw new MemoryException(MemoryStatus.UNSAFE_CONTENT, "记忆包含不安全内容");
        }
    }

    public int limit(MemoryKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case PERSON_PROFILE -> properties.getPersonProfileLimit();
            case COLLABORATION_PREFERENCE -> properties.getCollaborationPreferenceLimit();
        };
    }

    private static boolean hasDangerousControl(String content) {
        return content.codePoints()
                .anyMatch(
                        codePoint ->
                                (Character.isISOControl(codePoint)
                                                && codePoint != '\n'
                                                && codePoint != '\r'
                                                && codePoint != '\t')
                                        || codePoint == 0x202A
                                        || codePoint == 0x202B
                                        || codePoint == 0x202D
                                        || codePoint == 0x202E
                                        || codePoint == 0x2066
                                        || codePoint == 0x2067
                                        || codePoint == 0x2068
                                        || codePoint == 0x2069);
    }

    private static MemoryException invalid() {
        return new MemoryException(MemoryStatus.INVALID_REQUEST, "记忆不能为空");
    }
}
