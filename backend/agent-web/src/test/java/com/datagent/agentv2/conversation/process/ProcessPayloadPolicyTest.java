package com.datagent.agentv2.conversation.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ProcessPayloadPolicyTest {

    private static final int LIMIT_BYTES = 10;
    private static final String SECRET_VALUE = "secret-value";
    private static final String REDACTED_VALUE = "[REDACTED]";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ProcessPayloadSanitizer sanitizer = new ProcessPayloadSanitizer(json);
    private final ProcessPayloadLimiter limiter = new ProcessPayloadLimiter(json);

    @Test
    void recursivelySanitizesJsonObjectsArraysAndTextPairs() {
        JsonNode source =
                json.readTree(
                        """
                        {
                          "token": "secret-value",
                          "nested": [{"access_token": "secret-value", "safe": "kept"}],
                          "connectionString": "jdbc:postgresql://example"
                        }
                        """);

        JsonNode sanitized = sanitizer.sanitizeJson(source);

        assertThat(sanitized.path("token").asString()).isEqualTo(REDACTED_VALUE);
        assertThat(sanitized.path("nested").get(0).path("access_token").asString())
                .isEqualTo(REDACTED_VALUE);
        assertThat(sanitized.path("nested").get(0).path("safe").asString()).isEqualTo("kept");
        assertThat(sanitized.path("connectionString").asString()).isEqualTo(REDACTED_VALUE);
        assertThat(sanitizer.sanitizeText("token=secret-value; password: secret-value"))
                .doesNotContain(SECRET_VALUE)
                .contains("token=" + REDACTED_VALUE, "password: " + REDACTED_VALUE);
    }

    @Test
    void boundsTextAtExactUtf8ByteThresholdsAndHashesTheFullSafeValue() {
        ProcessPayloadLimiter.BoundedTextResult below =
                limiter.boundText(textWithUtf8Bytes(LIMIT_BYTES - 1), LIMIT_BYTES);
        ProcessPayloadLimiter.BoundedTextResult exact =
                limiter.boundText(textWithUtf8Bytes(LIMIT_BYTES), LIMIT_BYTES);
        ProcessPayloadLimiter.BoundedTextResult above =
                limiter.boundText(textWithUtf8Bytes(LIMIT_BYTES + 1), LIMIT_BYTES);

        assertThat(below.truncated()).isFalse();
        assertThat(exact.truncated()).isFalse();
        assertThat(above.truncated()).isTrue();
        assertThat(above.originalContentSizeBytes()).isEqualTo(LIMIT_BYTES + 1L);
        assertThat(above.content().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(LIMIT_BYTES);
        assertThat(above.content()).doesNotContain("�");
        assertThat(above.contentSha256()).isEqualTo(sha256(textWithUtf8Bytes(LIMIT_BYTES + 1)));
    }

    @Test
    void truncatedJsonStoresNullValueAndAValidUtf8PreviewWithoutChangingFullMetadata() {
        JsonNode safe =
                sanitizer.sanitizeJson(json.valueToTree(java.util.Map.of("内容", "中".repeat(30))));

        BoundedProcessPayload payload = limiter.boundJson(safe, 32);
        JsonNode persistedDocument = json.valueToTree(payload);

        assertThat(payload.truncated()).isTrue();
        assertThat(payload.value()).isNull();
        assertThat(payload.preview()).doesNotContain("�");
        assertThat(payload.preview().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(32);
        assertThat(payload.originalSizeBytes()).isEqualTo(json.writeValueAsBytes(safe).length);
        assertThat(payload.sha256()).isEqualTo(sha256(json.writeValueAsString(safe)));
        assertThat(persistedDocument.isObject()).isTrue();
        assertThat(persistedDocument.path("value").isNull()).isTrue();
    }

    @Test
    void untruncatedJsonKeepsTheStructuredValue() {
        JsonNode safe = sanitizer.sanitizeJson(json.readTree("{\"value\":1}"));

        BoundedProcessPayload payload = limiter.boundJson(safe, 1024);

        assertThat(payload.truncated()).isFalse();
        assertThat((JsonNode) json.valueToTree(payload.value())).isEqualTo(safe);
        assertThat(payload.preview()).isNull();
    }

    private static String textWithUtf8Bytes(int byteCount) {
        return "a".repeat(byteCount - 3) + "中";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
