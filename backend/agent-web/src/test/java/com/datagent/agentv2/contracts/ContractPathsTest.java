package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContractPathsTest {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace");
    private static final Map<String, Set<String>> REQUIRED = requiredOperations();

    @Test
    void resolvesContractsFromTheRepositoryRoot() {
        assertThat(ContractFiles.root()).isDirectory().hasFileName("contracts");
        assertThat(ContractFiles.openApi()).exists();
    }

    @Test
    void openApiDeclaresItsBaselineStructure() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());

        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.path("info").isObject()).isTrue();
        assertThat(api.path("paths").isObject()).isTrue();
        assertThat(api.path("components").path("schemas").isObject()).isTrue();
    }

    @Test
    void openApiDeclaresEveryFrozenV1ControllerOperation() throws IOException {
        JsonNode paths = YAML.readTree(ContractFiles.openApi().toFile()).path("paths");

        assertThat(REQUIRED.values().stream().mapToInt(Set::size).sum()).isEqualTo(40);
        assertThat(REQUIRED)
                .allSatisfy(
                        (path, methods) -> {
                            JsonNode operation = paths.path(path);
                            assertThat(operation.isObject()).as("path %s", path).isTrue();
                            assertThat(operation.fieldNames())
                                    .toIterable()
                                    .filteredOn(HTTP_METHODS::contains)
                                    .as("HTTP methods for %s", path)
                                    .containsExactlyInAnyOrderElementsOf(methods);
                        });
    }

    @Test
    void openApiDeclaresOperationMetadataCookieAndCoreDtos() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());

        REQUIRED.forEach(
                (path, methods) ->
                        methods.forEach(
                                method -> {
                                    JsonNode operation = api.path("paths").path(path).path(method);
                                    assertThat(operation.path("operationId").asText())
                                            .as("operationId for %s %s", method, path)
                                            .isNotBlank();
                                    assertThat(operation.path("security").isArray())
                                            .as("security for %s %s", method, path)
                                            .isTrue();
                                    assertThat(operation.path("responses").fieldNames())
                                            .toIterable()
                                            .as("successful response for %s %s", method, path)
                                            .anyMatch(status -> status.startsWith("2"));
                                }));

        JsonNode cookie = api.path("components").path("securitySchemes").path("sessionCookie");
        assertThat(cookie.path("type").asText()).isEqualTo("apiKey");
        assertThat(cookie.path("in").asText()).isEqualTo("cookie");
        assertThat(cookie.path("name").asText()).isEqualTo("DATA_AGENT_SESSION");

        JsonNode schemas = api.path("components").path("schemas");
        assertThat(schemas.fieldNames())
                .toIterable()
                .contains(
                        "AgentSession",
                        "AgentMessage",
                        "AgentRun",
                        "RunEvent",
                        "PendingInteraction",
                        "ProblemDetail",
                        "RunStream");
        assertThat(schemas.path("AgentRun").path("properties").path("status").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "idle",
                        "queued",
                        "running",
                        "waiting_for_user",
                        "waiting_confirmation",
                        "done",
                        "cancelled",
                        "failed");
        assertThat(schemas.path("AgentRun").path("properties").fieldNames())
                .toIterable()
                .contains("cancellationRequestedAt");
        assertThat(schemas.path("RunStream").path("properties").fieldNames())
                .toIterable()
                .contains(
                        "executionAttempt",
                        "modelTurnSequence",
                        "phase",
                        "streamSequence",
                        "reasoning",
                        "answer",
                        "checkpointedAt");
    }

    @Test
    void openApiFreezesIdempotentSubmissionAndRunControls() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());
        JsonNode paths = api.path("paths");

        assertThat(
                        paths.path("/api/v1/agent-sessions/{sessionId}/messages")
                                .path("post")
                                .path("parameters"))
                .extracting(node -> node.path("$ref").asText())
                .contains("#/components/parameters/IdempotencyKey");

        JsonNode cancel =
                paths.path("/api/v1/agent-sessions/{sessionId}/runs/{runId}:cancel").path("post");
        assertThat(cancel.path("responses").path("202").isObject()).isTrue();
        assertThat(
                        cancel.path("responses")
                                .path("202")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/AgentRun");

        JsonNode retry =
                paths.path("/api/v1/agent-sessions/{sessionId}/runs/{runId}:retry").path("post");
        assertThat(retry.path("responses").path("202").isObject()).isTrue();
        assertThat(retry.path("parameters"))
                .extracting(node -> node.path("$ref").asText())
                .contains("#/components/parameters/IdempotencyKey");

        assertThat(
                        api.path("components")
                                .path("responses")
                                .path("IdempotencyKeyReused")
                                .path("content")
                                .path("application/problem+json")
                                .path("example")
                                .path("code")
                                .asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    private static Map<String, Set<String>> requiredOperations() {
        Map<String, Set<String>> required = new LinkedHashMap<>();
        required.put("/api/v1/auth/login", Set.of("post"));
        required.put("/api/v1/auth/session", Set.of("get"));
        required.put("/api/v1/auth/logout", Set.of("post"));
        required.put("/api/v1/agent-sessions", Set.of("get", "post"));
        required.put("/api/v1/agent-sessions/{sessionId}", Set.of("get"));
        required.put("/api/v1/agent-sessions/{sessionId}/metric-identity", Set.of("put"));
        required.put("/api/v1/agent-sessions/{sessionId}/messages", Set.of("post"));
        required.put("/api/v1/agent-sessions/{sessionId}/runs/{runId}:cancel", Set.of("post"));
        required.put("/api/v1/agent-sessions/{sessionId}/runs/{runId}:retry", Set.of("post"));
        required.put("/api/v1/agent-sessions/{sessionId}/events", Set.of("get"));
        required.put(
                "/api/v1/agent-sessions/{sessionId}/confirmations/{confirmationId}",
                Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{sessionId}/interactions/{interactionId}:resolve",
                Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{sessionId}/interactions/{interactionId}:submit-secret",
                Set.of("post"));
        required.put("/api/v1/agent-sessions/{sessionId}/audit-log", Set.of("get"));
        required.put("/api/v1/metric-identities", Set.of("get", "post"));
        required.put("/api/v1/metric-identities/{id}", Set.of("get"));
        required.put("/api/v1/metric-identities/{id}:verify", Set.of("post"));
        required.put("/api/v1/metric-identities/{id}:replace-password", Set.of("post"));
        required.put("/api/v1/metric-identities/{id}:deactivate", Set.of("post"));
        required.put("/api/v1/metric-identities:rotate-secrets", Set.of("post"));
        required.put("/api/v1/tenants", Set.of("post"));
        required.put("/api/v1/tenants/{tenantId}/members", Set.of("get", "post"));
        required.put("/api/v1/artifacts/{artifactId}", Set.of("get"));
        required.put("/api/v1/artifacts/{artifactId}/preview", Set.of("get"));
        required.put("/api/v1/artifacts/{artifactId}:create-download", Set.of("post"));
        required.put("/api/v1/artifact-downloads/{artifactId}", Set.of("get"));
        required.put("/api/v1/scheduled-tasks", Set.of("get", "post"));
        required.put("/api/v1/scheduled-tasks/{taskId}", Set.of("get", "put", "delete"));
        required.put("/api/v1/scheduled-tasks/{taskId}/executions", Set.of("get", "post"));
        required.put("/api/v1/task-executions/{executionId}", Set.of("get"));
        required.put("/api/v1/task-executions/{executionId}/cancel", Set.of("post"));
        required.put("/api/v1/task-executions/{executionId}/events", Set.of("get"));
        required.put("/api/v1/evaluation/mutation-guard", Set.of("get"));
        return Collections.unmodifiableMap(required);
    }
}
