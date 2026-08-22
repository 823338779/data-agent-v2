package com.datagent.agentv2.conversation.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.platform.context.TenantContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import tools.jackson.databind.json.JsonMapper;

class OllamaAgentModelGatewayTest {

    private static final String MODEL_NAME = "test-model";
    private static final String TENANT_ID = "tenant_local";
    private static final String FINAL_STREAM =
            """
            {"model":"test-model","created_at":"2026-08-01T00:00:00Z","message":{"role":"assistant","thinking":"inspect","content":""},"done":false}
            {"model":"test-model","created_at":"2026-08-01T00:00:01Z","message":{"role":"assistant","thinking":"","content":"answer"},"done":false}
            {"model":"test-model","created_at":"2026-08-01T00:00:02Z","message":{"role":"assistant","thinking":"","content":""},"done":true,"done_reason":"stop"}
            """;
    private static final String TOOL_STREAM =
            """
            {"model":"test-model","created_at":"2026-08-01T00:00:00Z","message":{"role":"assistant","content":"","tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":{"q":"x"}}}]},"done":true,"done_reason":"stop"}
            """;

    private final AtomicReference<String> response = new AtomicReference<>(FINAL_STREAM);
    private final AtomicReference<String> request = new AtomicReference<>();
    private HttpServer server;
    private OllamaAgentModelGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", this::respond);
        server.start();
        OllamaApi api =
                OllamaApi.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build();
        gateway =
                new OllamaAgentModelGateway(
                        api,
                        new OllamaModelProperties(MODEL_NAME, Duration.ofSeconds(3), 512),
                        JsonMapper.builder().build());
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void enablesProviderThinkingAndStreamsTypedDeltasInProviderOrder() {
        List<ModelOutputDelta> deltas = new ArrayList<>();
        List<String> callbackTenants = new ArrayList<>();

        ModelInvocationResult result =
                complete(
                        List.of(ModelInputMessage.plain("user", "question")),
                        List.of(),
                        delta -> {
                            callbackTenants.add(TenantContext.requiredTenantId());
                            deltas.add(delta);
                        });

        assertThat(deltas)
                .containsExactly(
                        ModelOutputDelta.thinking("inspect", "ollama", MODEL_NAME),
                        ModelOutputDelta.answer("answer", "ollama", MODEL_NAME));
        assertThat(callbackTenants).containsOnly(TENANT_ID);
        assertThat(result).isEqualTo(new ModelInvocationResult.FinalAnswer("answer"));
        assertThat(request.get())
                .contains("\"model\":\"test-model\"", "\"num_ctx\":65536", "\"num_predict\":512");
        assertThat(request.get()).contains("\"think\":true");
    }

    @Test
    void returnsOnlyCompleteToolCalls() {
        response.set(TOOL_STREAM);

        ModelInvocationResult result =
                complete(
                        List.of(ModelInputMessage.plain("user", "question")),
                        List.of(
                                new ModelToolDefinition(
                                        "lookup",
                                        "Lookup",
                                        Map.of("type", "object", "properties", Map.of()))),
                        ignored -> {});

        assertThat(result)
                .isEqualTo(
                        new ModelInvocationResult.ToolCalls(
                                List.of(
                                        new com.datagent.agentv2.conversation.react.ModelToolCall(
                                                "call-1",
                                                "lookup",
                                                JsonMapper.builder()
                                                        .build()
                                                        .readTree("{\"q\":\"x\"}")))));
    }

    @Test
    void malformedInterruptedStreamRaisesRecognizableFailure() {
        response.set("{\"message\":");

        assertThatThrownBy(
                        () ->
                                complete(
                                        List.of(ModelInputMessage.plain("user", "question")),
                                        List.of(),
                                        ignored -> {}))
                .isInstanceOf(ModelStreamInterruptedException.class);
    }

    private ModelInvocationResult complete(
            List<ModelInputMessage> messages,
            List<ModelToolDefinition> tools,
            Consumer<ModelOutputDelta> outputDeltaConsumer) {
        return TenantContext.call(
                TENANT_ID, () -> gateway.complete(messages, tools, outputDeltaConsumer));
    }

    private void respond(HttpExchange exchange) throws IOException {
        request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
