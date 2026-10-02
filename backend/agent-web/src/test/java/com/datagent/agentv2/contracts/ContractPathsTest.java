package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContractPathsTest {

    private static final String SESSION_PATH = "/api/v1/agent-sessions/{conversationSessionId}";
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace");
    private static final Map<String, Set<String>> REQUIRED = requiredOperations();

    @Test
    void openApiDeclaresOnlyTheCurrentConversationControllerOperations() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());
        JsonNode paths = api.path("paths");

        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(paths.size()).isEqualTo(REQUIRED.size());
        assertThat(REQUIRED.values().stream().mapToInt(Set::size).sum()).isEqualTo(20);
        assertThat(REQUIRED)
                .allSatisfy(
                        (path, methods) -> {
                            JsonNode operation = paths.path(path);
                            assertThat(operation.fieldNames())
                                    .toIterable()
                                    .filteredOn(HTTP_METHODS::contains)
                                    .containsExactlyInAnyOrderElementsOf(methods);
                            methods.forEach(
                                    method -> {
                                        JsonNode details = operation.path(method);
                                        assertThat(details.path("operationId").asText())
                                                .isNotBlank();
                                        assertThat(details.has("security")).isFalse();
                                        assertThat(details.path("responses").fieldNames())
                                                .toIterable()
                                                .anyMatch(status -> status.startsWith("2"));
                                    });
                        });
    }

    @Test
    void openApiExposesOnlyLocalExecutionStatesAndCurrentFields() throws IOException {
        JsonNode schemas =
                YAML.readTree(ContractFiles.openApi().toFile()).path("components").path("schemas");

        assertThat(schemas.fieldNames())
                .toIterable()
                .contains(
                        "ConversationSession",
                        "ConversationMessage",
                        "AgentExecution",
                        "AgentExecutionEvent",
                        "AgentExecutionStreamCheckpoint",
                        "ProblemDetail");
        assertThat(schemas.path("AgentExecution").path("properties").path("status").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("running", "done", "cancelled", "failed");
        assertThat(schemas.path("ConversationSession").path("properties").fieldNames())
                .toIterable()
                .doesNotContain(
                        "sessionType",
                        "metricPlatformCredentialBindingVersion",
                        "metricPlatformCredential");
        assertThat(schemas.path("ConversationMessage").path("properties").has("citations"))
                .isFalse();
        assertThat(schemas.path("AgentExecution").path("properties").fieldNames())
                .toIterable()
                .doesNotContain("cancellationRequestedAt");
        assertThat(schemas.path("AgentExecutionStreamCheckpoint").path("properties").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "modelInvocationSequence", "phase", "outputDeltaSequence", "answer");
    }

    @Test
    void submissionKeepsIdempotencyKey() throws IOException {
        JsonNode paths = YAML.readTree(ContractFiles.openApi().toFile()).path("paths");

        assertThat(paths.path(SESSION_PATH + "/messages").path("post").path("parameters"))
                .extracting(node -> node.path("$ref").asText())
                .contains("#/components/parameters/IdempotencyKey");
    }

    private static Map<String, Set<String>> requiredOperations() {
        Map<String, Set<String>> required = new LinkedHashMap<>();
        required.put("/api/v1/agent-sessions", Set.of("get", "post"));
        required.put("/api/v1/agent-sidebar", Set.of("get"));
        required.put("/api/v1/agent-projects", Set.of("post"));
        required.put("/api/v1/agent-projects/{projectId}", Set.of("patch", "delete"));
        required.put(SESSION_PATH + "/organization", Set.of("put"));
        required.put("/api/v1/agent-sessions/summary-events", Set.of("get"));
        required.put(SESSION_PATH, Set.of("get", "patch"));
        required.put(SESSION_PATH + "/messages", Set.of("post"));
        required.put(SESSION_PATH + "/executions/{executionId}:cancel", Set.of("post"));
        required.put(SESSION_PATH + "/events", Set.of("get"));
        String gitPath = "/api/v1/agent-sessions/{sessionId}/git-changes/{changeId}";
        required.put(gitPath, Set.of("get"));
        required.put(gitPath + "/files", Set.of("get"));
        required.put(gitPath + "/diff", Set.of("get"));
        for (String action : java.util.List.of("confirm", "cancel", "retry", "reconcile"))
            required.put(gitPath + "/" + action, Set.of("post"));
        return required;
    }
}
