package com.datagent.agentv2.conversation.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ThinkingProcessAuditLoggerTest {

    private static final String SECRET_VALUE = "secret-value";
    private static final String FULL_TAIL = "tail-beyond-database-preview";
    private static final UUID PROCESS_ITEM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ProcessPayloadSanitizer sanitizer = new ProcessPayloadSanitizer(json);

    @Test
    void auditUsesSameUuidAndFullSafePayloadEvenWhenDatabasePayloadIsTruncated() {
        ch.qos.logback.classic.Logger audit =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger("agent.conversation.process.audit");
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        audit.addAppender(events);
        ThinkingProcessAuditLogger logger = new ThinkingProcessAuditLogger(json);
        ProcessAuditEvent event = truncatedToolEventWithSecret();

        try {
            logger.onCommitted(event);
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains(PROCESS_ITEM_ID.toString(), FULL_TAIL, "[REDACTED]")
                    .doesNotContain(SECRET_VALUE);
        } finally {
            audit.detachAppender(events);
            events.stop();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"add", "list", "failure", "unknown-code"})
    void memoryAuditOmitsPersonalInputOutputPreviewAndErrorText(String action) {
        var audit =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger("agent.conversation.process.audit");
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        audit.addAppender(events);
        var content =
                json.valueToTree(
                        Map.of(
                                "action",
                                action,
                                "content",
                                "个人正文",
                                "evidence_message_ids",
                                java.util.List.of("用户证据"),
                                "status",
                                action.equals("unknown-code") ? "个人正文" : "CREATED"));
        var payload =
                new BoundedProcessPayload(
                        content, null, false, 20L, "a".repeat(64), "application/json");
        var now = Instant.parse("2026-09-16T00:00:00Z");
        var item =
                new ToolThinkingProcessItem(
                        PROCESS_ITEM_ID,
                        1,
                        action.equals("failure")
                                ? ThinkingProcessStatus.FAILED
                                : ThinkingProcessStatus.SUCCEEDED,
                        now,
                        now,
                        now,
                        0L,
                        1,
                        "memory",
                        "call",
                        1,
                        payload,
                        new BoundedProcessPayload(
                                null, "正文预览", true, 20L, "b".repeat(64), "application/json"),
                        action.equals("unknown-code") ? "个人正文" : "UNAVAILABLE",
                        "错误中的个人正文");
        try {
            new ThinkingProcessAuditLogger(json)
                    .onCommitted(new ProcessAuditEvent(item, "思考中的个人正文", content, content));
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains(PROCESS_ITEM_ID.toString(), "memory")
                    .doesNotContain(
                            "个人正文", "用户证据", "正文预览", "content", "fullToolInput", "fullToolOutput");
            assertThat(item.input().value()).isEqualTo(content);
        } finally {
            audit.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void auditSinkFailureDoesNotEscapeAfterCommitListener() {
        ObjectMapper failingJson = mock(ObjectMapper.class);
        when(failingJson.writeValueAsString(any()))
                .thenThrow(new IllegalStateException("sink unavailable"));
        ThinkingProcessAuditLogger logger = new ThinkingProcessAuditLogger(failingJson);

        assertThatCode(() -> logger.onCommitted(truncatedToolEventWithSecret()))
                .doesNotThrowAnyException();
    }

    private ProcessAuditEvent truncatedToolEventWithSecret() {
        JsonNode fullInput =
                sanitizer.sanitizeJson(
                        json.valueToTree(Map.of("token", SECRET_VALUE, "query", "select 1")));
        JsonNode fullOutput =
                sanitizer.sanitizeJson(
                        json.valueToTree(Map.of("result", FULL_TAIL, "password", SECRET_VALUE)));
        BoundedProcessPayload truncatedInput =
                new BoundedProcessPayload(
                        null,
                        "{\"token\":\"[REDACTED]\"",
                        true,
                        128L,
                        "a".repeat(64),
                        "application/json");
        BoundedProcessPayload truncatedOutput =
                new BoundedProcessPayload(
                        null, "{\"result\":\"tail", true, 256L, "b".repeat(64), "application/json");
        Instant startedAt = Instant.parse("2026-08-04T01:00:00Z");
        ThinkingProcessItem item =
                new ToolThinkingProcessItem(
                        PROCESS_ITEM_ID,
                        1L,
                        ThinkingProcessStatus.SUCCEEDED,
                        startedAt,
                        startedAt,
                        startedAt.plusMillis(25),
                        25L,
                        ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                        "test_tool",
                        "tool_call_1",
                        1L,
                        truncatedInput,
                        truncatedOutput,
                        null,
                        null);
        return new ProcessAuditEvent(item, null, fullInput, fullOutput);
    }
}
