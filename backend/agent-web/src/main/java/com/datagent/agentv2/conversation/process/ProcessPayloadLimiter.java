package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 对单个安全 ProcessItem 载荷执行 UTF-8 字节计数、哈希和有界预览。 */
@Component
@RequiredArgsConstructor
public final class ProcessPayloadLimiter {

    private static final String SHA_256 = "SHA-256";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final ObjectMapper json;

    /** 按规范 JSON 的 UTF-8 字节大小决定保存结构化值还是安全预览。 */
    public BoundedProcessPayload boundJson(JsonNode sanitizedValue, int maxBytes) {
        requirePositiveLimit(maxBytes);
        JsonNode safeValue = Objects.requireNonNull(sanitizedValue, "sanitizedValue");
        byte[] canonical = json.writeValueAsBytes(safeValue);
        String hash = sha256(canonical);
        if (canonical.length <= maxBytes) {
            return new BoundedProcessPayload(
                    json.convertValue(safeValue, Object.class),
                    null,
                    false,
                    canonical.length,
                    hash,
                    CONTENT_TYPE_JSON);
        }
        return new BoundedProcessPayload(
                null,
                utf8Preview(canonical, maxBytes),
                true,
                canonical.length,
                hash,
                CONTENT_TYPE_JSON);
    }

    /** 按完整安全文本的 UTF-8 字节大小决定保存全文还是安全预览。 */
    public BoundedTextResult boundText(String sanitizedText, int maxBytes) {
        requirePositiveLimit(maxBytes);
        String safeText = Objects.requireNonNull(sanitizedText, "sanitizedText");
        byte[] bytes = safeText.getBytes(StandardCharsets.UTF_8);
        boolean truncated = bytes.length > maxBytes;
        return new BoundedTextResult(
                truncated ? utf8Preview(bytes, maxBytes) : safeText,
                truncated,
                bytes.length,
                sha256(bytes));
    }

    /** 在字节上限内回退到最后一个完整 UTF-8 code point。 */
    private static String utf8Preview(byte[] bytes, int maxBytes) {
        int length = Math.min(bytes.length, maxBytes);
        while (length > 0) {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length))
                        .toString();
            } catch (CharacterCodingException failure) {
                length--;
            }
        }
        return "";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void requirePositiveLimit(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
    }

    /** thinking 正文在数据库中的有界表示及完整安全文本元数据。 */
    public record BoundedTextResult(
            String content,
            boolean truncated,
            long originalContentSizeBytes,
            String contentSha256) {}
}
