package com.datagent.agentv2.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.memory.MemoryCommand;
import com.datagent.agentv2.memory.MemoryEntry;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryOutcome;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemoryService;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.MemorySource;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.memory.MemoryStatus;
import com.datagent.agentv2.memory.MemoryWriteContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MemoryToolTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final MemoryService service = mock(MemoryService.class);
    private final MemoryInvocationRegistry registry = new MemoryInvocationRegistry();
    private final MemoryTool tool =
            new MemoryTool(new MemoryToolDispatcher(service, json), registry, json);

    @BeforeEach
    void openInvocation() {
        registry.open("execution-1", 1, snapshot(4, true), List.of(evidence("message-1")));
    }

    @Test
    void exposesConditionalSchemaAndSupportsIdempotency() {
        JsonNode schema = tool.parametersSchema();

        assertThat(tool.name()).isEqualTo("memory");
        assertThat(tool.supportsIdempotency()).isTrue();
        assertThat(schema.path("oneOf")).hasSize(4);
        assertThat(schema.toString()).contains("add", "list", "replace", "remove");
        assertThat(schema.toString()).doesNotContain("confirm", "review_mode", "prompt_version");
    }

    @Test
    void dispatchesAddWithTrustedContextAndAdvancesCommittedRevision() throws Exception {
        when(service.execute(any(), any()))
                .thenReturn(
                        new MemoryOutcome(
                                MemoryStatus.CREATED,
                                "memory-1",
                                1L,
                                5,
                                true,
                                true,
                                quotas()));
        JsonNode arguments =
                json.readTree(
                        """
                        {"action":"add","kind":"person_profile","content":"我住在杭州",\
                        "write_reason":"user_requested","evidence_message_ids":["message-1"]}
                        """);

        JsonNode result = tool.execute(arguments, invocation());

        assertThat(result.path("status").asString()).isEqualTo("CREATED");
        ArgumentCaptor<MemoryWriteContext> context =
                ArgumentCaptor.forClass(MemoryWriteContext.class);
        ArgumentCaptor<MemoryCommand> command = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(service).execute(context.capture(), command.capture());
        assertThat(context.getValue().expectedRevision()).isEqualTo(4);
        assertThat(context.getValue().promptVersion()).isEqualTo(MemoryToolPrompts.MAIN_VERSION);
        assertThat(command.getValue()).isInstanceOf(MemoryCommand.Add.class);
        assertThat(registry.contextFor(invocation()).expectedRevision()).isEqualTo(5);
    }

    @Test
    void businessRejectionThrowsStructuredFailureAndDoesNotAdvance() {
        when(service.execute(any(), any()))
                .thenReturn(
                        new MemoryOutcome(
                                MemoryStatus.QUOTA_EXCEEDED,
                                null,
                                null,
                                9,
                                false,
                                false,
                                quotas()));

        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                """
                                                {"action":"add","kind":"person_profile","content":"内容",\
                                                "write_reason":"agent_inferred","evidence_message_ids":["message-1"]}
                                                """),
                                        invocation()))
                .isInstanceOfSatisfying(
                        ToolBusinessException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("QUOTA_EXCEEDED");
                            assertThat(failure.details().path("status").asString())
                                    .isEqualTo("QUOTA_EXCEEDED");
                        });
        assertThat(registry.contextFor(invocation()).expectedRevision()).isEqualTo(4);
    }

    @Test
    void replayedHistoricalSuccessNeverMovesFrozenRevisionBackward() throws Exception {
        when(service.execute(any(), any()))
                .thenReturn(
                        new MemoryOutcome(
                                MemoryStatus.ALREADY_EXISTS,
                                "memory-1",
                                1L,
                                2,
                                false,
                                true,
                                quotas()));

        tool.execute(
                json.readTree(
                        """
                        {"action":"add","kind":"person_profile","content":"内容",\
                        "write_reason":"user_requested","evidence_message_ids":["message-1"]}
                        """),
                invocation());

        assertThat(registry.contextFor(invocation()).expectedRevision()).isEqualTo(4);
    }

    @Test
    void rejectsMissingConditionalArgumentsPrivateConfirmAndForgedServerFields() {
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                """
                                                {"action":"add","kind":"person_profile","content":"内容",\
                                                "write_reason":"user_requested"}
                                                """),
                                        invocation()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                """
                                                {"action":"confirm","memory_id":"memory-1",\
                                                "expected_version":1,"evidence_message_ids":["message-1"]}
                                                """),
                                        invocation()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                """
                                                {"action":"list","review_mode":true,"user_id":"other"}
                                                """),
                                        invocation()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                """
                                                {"action":"add","kind":"person_profile","content":"内容",\
                                                "write_reason":"user_requested","evidence_message_ids":["message-2"]}
                                                """),
                                        invocation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frozen scope");
        verifyNoInteractions(service);
    }

    @Test
    void listsCurrentSnapshotWithStableCursorWithoutRefreshingWriteRevision() throws Exception {
        MemoryEntry first = entry("memory-1", MemoryKind.PERSON_PROFILE, "杭州");
        MemoryEntry second = entry("memory-2", MemoryKind.PERSON_PROFILE, "偏好简洁回答");
        when(service.snapshot())
                .thenReturn(
                        new MemorySnapshot(
                                snapshot(9, true).state(), List.of(second, first), quotas()));

        JsonNode result =
                tool.execute(
                        json.readTree(
                                """
                                {"action":"list","kind":"person_profile","limit":1}
                                """),
                        invocation());

        assertThat(result.path("revision").asLong()).isEqualTo(9);
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("items").get(0).path("memoryId").asString())
                .isEqualTo("memory-1");
        assertThat(result.path("nextCursor").asString()).isEqualTo("memory-1");
        assertThat(result.toString()).doesNotContain("normalizedHash", "normalizationVersion");
        verify(service).snapshot();
        assertThat(registry.contextFor(invocation()).expectedRevision()).isEqualTo(4);
    }

    @Test
    void listAfterWriteSeesCommittedEntryWithoutChangingAdvancedWriteRevision() throws Exception {
        MemoryEntry created = entry("memory-1", MemoryKind.PERSON_PROFILE, "杭州");
        when(service.execute(any(), any()))
                .thenReturn(
                        new MemoryOutcome(
                                MemoryStatus.CREATED,
                                created.memoryId(),
                                created.version(),
                                5,
                                true,
                                true,
                                quotas()));
        when(service.snapshot())
                .thenReturn(
                        new MemorySnapshot(
                                snapshot(5, true).state(), List.of(created), quotas()));

        tool.execute(
                json.readTree(
                        """
                        {"action":"add","kind":"person_profile","content":"杭州",\
                        "write_reason":"user_requested","evidence_message_ids":["message-1"]}
                        """),
                invocation());
        JsonNode listed =
                tool.execute(json.readTree("{\"action\":\"list\"}"), invocation());

        assertThat(listed.path("revision").asLong()).isEqualTo(5);
        assertThat(listed.path("items").get(0).path("content").asString()).isEqualTo("杭州");
        assertThat(registry.contextFor(invocation()).expectedRevision()).isEqualTo(5);
    }

    @Test
    void externalChangeIsVisibleToListButNextWriteKeepsFrozenRevisionAndConflicts()
            throws Exception {
        MemoryEntry external = entry("memory-1", MemoryKind.PERSON_PROFILE, "外部新内容");
        when(service.snapshot())
                .thenReturn(
                        new MemorySnapshot(
                                snapshot(9, true).state(), List.of(external), quotas()));
        when(service.execute(any(), any()))
                .thenReturn(
                        new MemoryOutcome(
                                MemoryStatus.CONFLICT,
                                external.memoryId(),
                                external.version(),
                                9,
                                false,
                                true,
                                quotas()));

        JsonNode listed =
                tool.execute(json.readTree("{\"action\":\"list\"}"), invocation());
        assertThat(listed.path("items").get(0).path("content").asString())
                .isEqualTo("外部新内容");
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree(
                                                """
                                                {"action":"replace","memory_id":"memory-1",\
                                                "expected_version":1,"new_content":"我的修改",\
                                                "write_reason":"user_requested",\
                                                "evidence_message_ids":["message-1"]}
                                                """),
                                        invocation()))
                .isInstanceOfSatisfying(
                        ToolBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CONFLICT"));
        ArgumentCaptor<MemoryWriteContext> context =
                ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(service).execute(context.capture(), any());
        assertThat(context.getValue().expectedRevision()).isEqualTo(4);
        assertThat(registry.contextFor(invocation()).expectedRevision()).isEqualTo(4);
    }

    @Test
    void listCannotBypassDisabledReadSetting() {
        registry.close("execution-1", 1);
        registry.open(
                "execution-1", 1, snapshot(4, false), List.of(evidence("message-1")));
        when(service.snapshot()).thenReturn(snapshot(4, false));

        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree("{\"action\":\"list\"}"), invocation()))
                .isInstanceOfSatisfying(
                        ToolBusinessException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("INVALID_REQUEST");
                            assertThat(failure.details().path("status").asString())
                                    .isEqualTo("INVALID_REQUEST");
                        });
        verify(service).snapshot();
    }

    private AgentTool.InvocationContext invocation() {
        return new AgentTool.InvocationContext(
                "tenant_local", "execution-1", 1, "call-1", "execution-1:call-1");
    }

    private static MemorySnapshot snapshot(long revision, boolean readEnabled) {
        return new MemorySnapshot(
                new MemoryState(
                        revision, 0, readEnabled, true, true, 1, LocalDateTime.now()),
                List.of(),
                quotas());
    }

    private static Map<MemoryKind, MemoryQuota> quotas() {
        return Map.of(
                MemoryKind.PERSON_PROFILE,
                new MemoryQuota(0, 800),
                MemoryKind.COLLABORATION_PREFERENCE,
                new MemoryQuota(0, 1500));
    }

    private static MemoryEvidence evidence(String id) {
        return new MemoryEvidence(
                id,
                MemoryEvidence.Kind.USER_MESSAGE,
                "conversation-1",
                "execution-1",
                1,
                "user_admin",
                1,
                "请记住",
                LocalDateTime.now());
    }

    private static MemoryEntry entry(String id, MemoryKind kind, String content) {
        LocalDateTime now = LocalDateTime.now();
        return new MemoryEntry(
                id,
                kind,
                content,
                "hash-" + id,
                1,
                content.length(),
                MemorySource.USER_DIRECT,
                true,
                1,
                now,
                now,
                now,
                MemoryToolPrompts.MAIN_VERSION);
    }
}
