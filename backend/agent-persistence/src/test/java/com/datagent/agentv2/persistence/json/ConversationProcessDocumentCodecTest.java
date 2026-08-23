package com.datagent.agentv2.persistence.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationProcessDocumentCodecTest {

    private static final UUID THINKING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant START = Instant.parse("2026-08-04T01:00:00Z");
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    private final ConversationProcessDocumentCodec codec = new ConversationProcessDocumentCodec();

    @Test
    void roundTripsOrderedPolymorphicProcessItems() {
        List<ThinkingProcessItem> items =
                List.of(
                        new ModelThinkingProcessItem(
                                THINKING_ID,
                                1L,
                                ThinkingProcessStatus.SUCCEEDED,
                                START,
                                START,
                                START.plusMillis(7),
                                7L,
                                1,
                                "分析问题",
                                false,
                                12L,
                                SHA_A,
                                1L,
                                "codex",
                                "gpt-5"),
                        new ToolThinkingProcessItem(
                                TOOL_ID,
                                2L,
                                ThinkingProcessStatus.SUCCEEDED,
                                START,
                                START.plusMillis(8),
                                START.plusMillis(18),
                                10L,
                                1,
                                "Current time",
                                "call_1",
                                1L,
                                new BoundedProcessPayload(
                                        Map.of("zone", "Asia/Shanghai"),
                                        null,
                                        false,
                                        24L,
                                        SHA_B,
                                        CONTENT_TYPE_JSON),
                                new BoundedProcessPayload(
                                        Map.of("time", "09:00"),
                                        null,
                                        false,
                                        17L,
                                        SHA_C,
                                        CONTENT_TYPE_JSON),
                                null,
                                null));

        String document = codec.write(items);

        assertThat(document).startsWith("[");
        assertThat(document)
                .contains("\"toolName\"")
                .contains("\"toolCallId\"")
                .doesNotContain("\"toolId\"")
                .doesNotContain("\"toolExecutionId\"")
                .doesNotContain("\"success\"");
        assertThat(codec.read(document)).containsExactlyElementsOf(items);
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> codec.read("[{\"type\":\"UNKNOWN\",\"schemaVersion\":1}]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        assertThatThrownBy(() -> codec.read("[{\"type\":\"THINKING\",\"schemaVersion\":2}]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsDuplicateProcessItemIdentityAndSequence() {
        String duplicate =
                """
                [
                  {"processItemId":"00000000-0000-0000-0000-000000000001","sequence":1,
                   "type":"THINKING","status":"RUNNING","createdAt":"2026-08-04T01:00:00Z",
                   "startedAt":"2026-08-04T01:00:00Z","completedAt":null,"durationMillis":null,
                   "schemaVersion":1,"content":"a","contentTruncated":false,
                   "originalContentSizeBytes":1,"contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "modelInvocationSequence":1,"modelProvider":"codex","modelName":"gpt-5"},
                  {"processItemId":"00000000-0000-0000-0000-000000000001","sequence":1,
                   "type":"THINKING","status":"RUNNING","createdAt":"2026-08-04T01:00:00Z",
                   "startedAt":"2026-08-04T01:00:00Z","completedAt":null,"durationMillis":null,
                   "schemaVersion":1,"content":"b","contentTruncated":false,
                   "originalContentSizeBytes":1,"contentSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "modelInvocationSequence":2,"modelProvider":"codex","modelName":"gpt-5"}
                ]
                """;

        assertThatThrownBy(() -> codec.read(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsPreparedThinkingAndInvalidTerminalTiming() {
        String invalid =
                """
                [{"processItemId":"00000000-0000-0000-0000-000000000001","sequence":1,
                  "type":"THINKING","status":"PREPARED","createdAt":"2026-08-04T01:00:00Z",
                  "startedAt":null,"completedAt":null,"durationMillis":null,"schemaVersion":1,
                  "content":"a","contentTruncated":false,"originalContentSizeBytes":1,
                  "contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "modelInvocationSequence":1,"modelProvider":"codex","modelName":"gpt-5"}]
                """;

        assertThatThrownBy(() -> codec.read(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("THINKING");
    }
}
