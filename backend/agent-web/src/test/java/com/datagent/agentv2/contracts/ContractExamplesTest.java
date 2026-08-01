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

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final JsonMapper JSON = new JsonMapper();
    private static final Dialect CONTRACT_DIALECT =
            Dialect.builder(Draft202012.getInstance())
                    .keyword(new NonValidationKeyword("x-v2-extension"))
                    .keyword(new NonValidationKeyword("x-frontend-projection"))
                    .build();
    private static final Set<String> SESSION_EVENTS =
            Set.of(
                    "run_started",
                    "user_message",
                    "assistant_message",
                    "reasoning_delta",
                    "assistant_delta",
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
    void everySessionSseExampleConformsToTheSchema() throws IOException {
        tools.jackson.databind.JsonNode schemaNode =
                JSON.readTree(ContractFiles.schema("session-sse-event.schema.json"));
        Schema schema = SchemaRegistry.withDefaultDialect(CONTRACT_DIALECT).getSchema(schemaNode);
        List<java.nio.file.Path> examples;
        try (var paths = Files.list(ContractFiles.root().resolve("examples/sse"))) {
            examples = paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }

        assertThat(examples).hasSize(6);
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
