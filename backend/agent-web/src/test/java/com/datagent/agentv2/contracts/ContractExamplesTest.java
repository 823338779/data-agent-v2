package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Draft202012;
import com.networknt.schema.keyword.NonValidationKeyword;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ContractExamplesTest {

    private static final String COMPOSITE_CURSOR_PATTERN =
            "^e=[0-9]+;a=[0-9]+;t=[0-9]+;s=[0-9]+$";
    private static final String STREAM_SNAPSHOT = "stream_snapshot";

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final JsonMapper JSON = new JsonMapper();
    private static final Dialect CONTRACT_DIALECT =
            Dialect.builder(Draft202012.getInstance())
                    .keyword(new NonValidationKeyword("x-v2-extension"))
                    .keyword(new NonValidationKeyword("x-frontend-projection"))
                    .keyword(new NonValidationKeyword("x-sse-event-id"))
                    .keyword(new NonValidationKeyword("x-sensitive-payload-fields-forbidden"))
                    .build();
    private static final Set<String> SESSION_EVENTS =
            Set.of(
                    "run_started",
                    "user_message",
                    "assistant_message",
                    "reasoning_delta",
                    "assistant_delta",
                    STREAM_SNAPSHOT,
                    "run_phase_changed",
                    "skill_activated",
                    "context_compaction_started",
                    "context_compaction_completed",
                    "context_compaction_failed",
                    "tool_call_started",
                    "tool_call_completed",
                    "tool_call_result",
                    "tool_call_failed",
                    "interaction_requested",
                    "interaction_resolved",
                    "user_confirmation_requested",
                    "user_confirmation_resolved",
                    "run_completed",
                    "run_failed",
                    "run_cancelled",
                    "error");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "auth-login-failed.yaml",
                "auth-session.yaml",
                "session-create.yaml",
                "session-queued.yaml",
                "session-running.yaml",
                "problem-validation.yaml"
            })
    void restExampleIsValidYamlAndDeclaresItsSchema(String file) throws IOException {
        JsonNode example =
                YAML.readTree(ContractFiles.root().resolve("examples/rest").resolve(file).toFile());

        assertThat(example.path("schema").asText()).isNotBlank();
        assertThat(example.path("value").isMissingNode()).isFalse();
    }

    @Test
    void sessionExamplesFreezeQueuedAndCancellationFields() throws IOException {
        JsonNode queued =
                YAML.readTree(
                        ContractFiles.root().resolve("examples/rest/session-queued.yaml").toFile());
        JsonNode running =
                YAML.readTree(
                        ContractFiles.root()
                                .resolve("examples/rest/session-running.yaml")
                                .toFile());

        assertThat(queued.path("value").path("run").path("status").asText()).isEqualTo("queued");
        assertThat(queued.path("value").path("run").path("stream").path("executionAttempt").asInt())
                .isZero();
        assertThat(queued.path("value").path("run").has("cancellationRequestedAt")).isTrue();
        assertThat(running.path("value").path("run").has("cancellationRequestedAt")).isTrue();
    }

    @Test
    void sessionSseSchemaDeclaresTheFrozenEventSurface() throws IOException {
        tools.jackson.databind.JsonNode schemaNode =
                JSON.readTree(ContractFiles.schema("session-sse-event.schema.json"));
        Set<String> actual = new HashSet<>();
        schemaNode
                .path("properties")
                .path("type")
                .path("enum")
                .forEach(node -> actual.add(node.asString()));

        assertThat(schemaNode.path("$schema").asString())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(actual).isEqualTo(SESSION_EVENTS);
        assertThat(schemaNode.path("oneOf")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(schemaNode.path("x-frontend-projection").path("reasoning_delta").asString())
                .isEqualTo("reasoning.delta");
        assertThat(schemaNode.path("x-frontend-projection").path("assistant_delta").asString())
                .isEqualTo("assistant.draft");
        assertThat(schemaNode.path("x-frontend-projection").path("run_phase_changed").asString())
                .isEqualTo("run.timeline");
        assertThat(schemaNode.toString()).contains("\"x-v2-extension\":true");
    }

    @Test
    void sessionStreamingContractFreezesCheckpointAndCompositeCursorFields() throws IOException {
        JsonNode openapi = YAML.readTree(ContractFiles.root().resolve("openapi.yaml").toFile());
        JsonNode runStream = openapi.path("components").path("schemas").path("RunStream");
        JsonNode eventPath =
                openapi.path("paths").path("/api/v1/agent-sessions/{sessionId}/events").path("get");
        tools.jackson.databind.JsonNode schema =
                JSON.readTree(ContractFiles.schema("session-sse-event.schema.json"));

        // RunStream 始终暴露当前 attempt/turn 的完整 checkpoint；queued 投影允许零值。
        assertThat(runStream.path("required").toString())
                .contains(
                        "executionAttempt",
                        "modelTurnSequence",
                        "streamSequence",
                        "reasoning",
                        "answer",
                        "checkpointedAt");
        assertThat(runStream.path("properties").path("executionAttempt").path("minimum").asInt())
                .isZero();
        assertThat(runStream.path("properties").path("modelTurnSequence").path("minimum").asInt())
                .isZero();

        // Last-Event-ID 使用稳定 e/a/t/s 编码，同时恢复持久事件与瞬时流位置。
        JsonNode lastEventId = eventPath.path("parameters").path(1);
        assertThat(lastEventId.path("name").asText()).isEqualTo("Last-Event-ID");
        assertThat(lastEventId.path("schema").path("pattern").asText())
                .isEqualTo(COMPOSITE_CURSOR_PATTERN);
        assertThat(schema.path("x-sse-event-id").path("pattern").asString())
                .isEqualTo(COMPOSITE_CURSOR_PATTERN);

        // Delta 带完整流坐标，snapshot 额外携带可收敛的累计正文与 checkpoint 时间。
        assertThat(schema.path("properties").path("type").path("enum").toString())
                .contains(STREAM_SNAPSHOT);
        assertThat(schema.path("properties").has("modelTurnSequence")).isTrue();
        assertThat(schema.path("properties").has("reasoning")).isTrue();
        assertThat(schema.path("properties").has("answer")).isTrue();
        assertThat(schema.path("properties").has("checkpointedAt")).isTrue();
        assertThat(schema.path("x-sensitive-payload-fields-forbidden").toString())
                .contains("reasoning", "prompt", "toolInput", "toolOutput");
    }

    @Test
    void everySessionSseExampleConformsToTheSchema() throws IOException {
        tools.jackson.databind.JsonNode schemaNode =
                JSON.readTree(ContractFiles.schema("session-sse-event.schema.json"));
        Schema schema = SchemaRegistry.withDefaultDialect(CONTRACT_DIALECT).getSchema(schemaNode);
        List<java.nio.file.Path> examples;
        try (var paths = Files.list(ContractFiles.root().resolve("examples/sse"))) {
            examples = paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }

        assertThat(examples).hasSize(7);
        for (java.nio.file.Path example : examples) {
            tools.jackson.databind.JsonNode value = JSON.readTree(example);
            assertThat(schema.validate(value)).as(example.getFileName().toString()).isEmpty();
        }
    }

    @Test
    void taskExecutionSseSchemaFreezesTheTimelineEnvelope() throws IOException {
        tools.jackson.databind.JsonNode schema =
                JSON.readTree(ContractFiles.schema("task-execution-sse-event.schema.json"));

        assertThat(schema.path("$schema").asString())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.path("required").toString())
                .contains("source", "sourceSequence", "occurredAt", "type", "payload");
        assertThat(schema.path("properties").path("source").path("enum").toString())
                .contains("SCHEDULER", "AGENT");
    }
}
