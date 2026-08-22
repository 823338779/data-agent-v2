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

    private static final String ACTIVITY_EVENTS_PATH = "/api/v1/agent-sessions/activity-events";
    private static final String CONVERSATION_SESSION_PATH =
            "/api/v1/agent-sessions/{conversationSessionId}";
    private static final String MARK_READ_PATH =
            "/api/v1/agent-sessions/{conversationSessionId}:mark-read";
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

        assertThat(REQUIRED.values().stream().mapToInt(Set::size).sum()).isEqualTo(37);
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
    void openApiDeclaresOperationMetadataAndCoreDtos() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());

        REQUIRED.forEach(
                (path, methods) ->
                        methods.forEach(
                                method -> {
                                    JsonNode operation = api.path("paths").path(path).path(method);
                                    assertThat(operation.path("operationId").asText())
                                            .as("operationId for %s %s", method, path)
                                            .isNotBlank();
                                    assertThat(operation.has("security"))
                                            .as("security omitted for %s %s", method, path)
                                            .isFalse();
                                    assertThat(operation.path("responses").fieldNames())
                                            .toIterable()
                                            .as("successful response for %s %s", method, path)
                                            .anyMatch(status -> status.startsWith("2"));
                                }));

        JsonNode schemas = api.path("components").path("schemas");
        assertThat(schemas.fieldNames())
                .toIterable()
                .contains(
                        "ConversationSession",
                        "ConversationMessage",
                        "AgentExecution",
                        "AgentExecutionEvent",
                        "PendingInteraction",
                        "ProblemDetail",
                        "AgentExecutionStreamCheckpoint");
        assertThat(schemas.path("AgentExecution").path("properties").path("status").path("enum"))
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
        assertThat(schemas.path("AgentExecution").path("properties").fieldNames())
                .toIterable()
                .contains("cancellationRequestedAt");
        assertThat(schemas.path("AgentExecutionStreamCheckpoint").path("properties").fieldNames())
                .toIterable()
                .contains(
                        "executionAttemptNumber",
                        "modelInvocationSequence",
                        "phase",
                        "outputDeltaSequence",
                        "answer",
                        "checkpointedAt");
        assertThat(
                        schemas.path("AgentExecutionStreamCheckpoint")
                                .path("properties")
                                .has("reasoning"))
                .isFalse();
    }

    @Test
    void openApiFreezesIdempotentSubmissionAndExecutionControls() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());
        JsonNode paths = api.path("paths");

        assertThat(
                        paths.path("/api/v1/agent-sessions/{conversationSessionId}/messages")
                                .path("post")
                                .path("parameters"))
                .extracting(node -> node.path("$ref").asText())
                .contains("#/components/parameters/IdempotencyKey");

        JsonNode cancel =
                paths.path(
                                "/api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:cancel")
                        .path("post");
        assertThat(cancel.path("responses").path("202").isObject()).isTrue();
        assertThat(
                        cancel.path("responses")
                                .path("202")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/AgentExecution");

        JsonNode retry =
                paths.path(
                                "/api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:retry")
                        .path("post");
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

    @Test
    void openApiFreezesPerUserActivityContracts() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());
        JsonNode schemas = api.path("components").path("schemas");

        // 会话投影只携带跨设备未读游标，不再公开手工排序状态。
        JsonNode session = schemas.path("ConversationSession");
        assertThat(session.path("required").toString())
                .contains("unread", "lastNotifiableEventSequence")
                .doesNotContain("sortOrder", "orderingVersion");
        assertThat(session.path("properties").path("unread").path("type").asText())
                .isEqualTo("boolean");

        // mark-read 只允许提交精确通知序号，避免客户端覆盖服务端状态。
        JsonNode markReadRequest = schemas.path("MarkConversationSessionReadRequest");
        assertThat(markReadRequest.path("additionalProperties").asBoolean()).isFalse();
        assertThat(markReadRequest.path("required").toString()).contains("upToSequence");
        assertThat(markReadRequest.path("properties").size()).isOne();

        // 标题更新必须是带会话版本的局部写入，避免覆盖其他会话字段或并发更新。
        JsonNode rename = api.path("paths").path(CONVERSATION_SESSION_PATH).path("patch");
        assertThat(
                        rename.path("requestBody")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/RenameConversationSessionRequest");
        JsonNode renameRequest = schemas.path("RenameConversationSessionRequest");
        assertThat(renameRequest.path("additionalProperties").asBoolean()).isFalse();
        assertThat(renameRequest.path("required").toString()).contains("title", "expectedVersion");

        assertThat(api.path("paths").has("/api/v1/agent-sessions:reorder")).isFalse();
        assertThat(schemas.has("ReorderConversationSessionsRequest")).isFalse();

        // Activity SSE 是可收敛快照流，不接收 Last-Event-ID 历史游标。
        JsonNode activityEvents = api.path("paths").path(ACTIVITY_EVENTS_PATH).path("get");
        assertThat(activityEvents.path("parameters")).isEmpty();
        assertThat(
                        activityEvents
                                .path("responses")
                                .path("200")
                                .path("content")
                                .path("text/event-stream")
                                .path("schema")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/ConversationActivitySnapshot");
    }

    private static Map<String, Set<String>> requiredOperations() {
        Map<String, Set<String>> required = new LinkedHashMap<>();
        required.put("/api/v1/agent-sessions", Set.of("get", "post"));
        required.put(ACTIVITY_EVENTS_PATH, Set.of("get"));
        required.put(CONVERSATION_SESSION_PATH, Set.of("get", "patch"));
        required.put(MARK_READ_PATH, Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{conversationSessionId}/metric-platform-credential",
                Set.of("put"));
        required.put("/api/v1/agent-sessions/{conversationSessionId}/messages", Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:cancel",
                Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:retry",
                Set.of("post"));
        required.put("/api/v1/agent-sessions/{conversationSessionId}/events", Set.of("get"));
        required.put(
                "/api/v1/agent-sessions/{conversationSessionId}/confirmations/{confirmationId}",
                Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{conversationSessionId}/interactions/{interactionId}:resolve",
                Set.of("post"));
        required.put(
                "/api/v1/agent-sessions/{conversationSessionId}/interactions/{interactionId}:submit-secret",
                Set.of("post"));
        required.put("/api/v1/agent-sessions/{conversationSessionId}/audit-log", Set.of("get"));
        required.put("/api/v1/metric-platform-credentials", Set.of("get", "post"));
        required.put("/api/v1/metric-platform-credentials/{id}", Set.of("get"));
        required.put("/api/v1/metric-platform-credentials/{id}:verify", Set.of("post"));
        required.put("/api/v1/metric-platform-credentials/{id}:replace-password", Set.of("post"));
        required.put("/api/v1/metric-platform-credentials/{id}:deactivate", Set.of("post"));
        required.put("/api/v1/metric-platform-credentials:rotate-secrets", Set.of("post"));
        required.put("/api/v1/query-result-artifacts/{queryResultArtifactId}", Set.of("get"));
        required.put(
                "/api/v1/query-result-artifacts/{queryResultArtifactId}/preview", Set.of("get"));
        required.put(
                "/api/v1/query-result-artifacts/{queryResultArtifactId}:create-download",
                Set.of("post"));
        required.put("/api/v1/query-result-downloads/{queryResultArtifactId}", Set.of("get"));
        required.put("/api/v1/scheduled-agent-tasks", Set.of("get", "post"));
        required.put(
                "/api/v1/scheduled-agent-tasks/{scheduledAgentTaskId}",
                Set.of("get", "put", "delete"));
        required.put(
                "/api/v1/scheduled-agent-tasks/{scheduledAgentTaskId}/executions",
                Set.of("get", "post"));
        required.put("/api/v1/scheduled-task-executions/{scheduledTaskExecutionId}", Set.of("get"));
        required.put(
                "/api/v1/scheduled-task-executions/{scheduledTaskExecutionId}/cancel",
                Set.of("post"));
        required.put(
                "/api/v1/scheduled-task-executions/{scheduledTaskExecutionId}/events",
                Set.of("get"));
        required.put("/api/v1/evaluation/mutation-guard", Set.of("get"));
        return Collections.unmodifiableMap(required);
    }
}
