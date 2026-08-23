package com.datagent.agentv2.conversation.model.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.context.ContextSummaryRequest;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;

class OllamaContextSummaryModelAdapterTest {

    private static final String MODEL_NAME = "summary-model";
    private static final String RESPONSE =
            """
            {"model":"summary-model","created_at":"2026-08-01T00:00:00Z","message":{"role":"assistant","content":"{\\"schemaVersion\\":1}"},"done":true,"done_reason":"stop","eval_count":37}
            """;
    private static final String SECRET_MARKER = "marker-secret-response-body";

    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>(RESPONSE);
    private final java.util.concurrent.atomic.AtomicInteger responseStatus =
            new java.util.concurrent.atomic.AtomicInteger(200);
    private HttpServer server;
    private OllamaContextSummaryModelAdapter adapter;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", this::respond);
        server.start();
        OllamaApi api =
                OllamaApi.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build();
        adapter =
                new OllamaContextSummaryModelAdapter(
                        api,
                        new OllamaModelProperties(
                                MODEL_NAME, Duration.ofSeconds(3), 4_096, 65_536, 4_096));
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void sendsANonStreamingNoToolsStructuredRequestWithLayerOutputLimit() {
        ContextSummaryResult result =
                adapter.summarize(
                        new ContextSummaryRequest(
                                "summary system prompt TARGET_RENDERED_TOKENS=256",
                                "source data",
                                "{\"type\":\"object\"}",
                                256,
                                2_048,
                                4_096));

        assertThat(result)
                .isEqualTo(
                        new ContextSummaryResult(
                                "{\"schemaVersion\":1}", 37, "ollama", MODEL_NAME));
        assertThat(requestBody.get())
                .contains(
                        "\"model\":\"summary-model\"",
                        "\"stream\":false",
                        "\"think\":false",
                        "\"num_ctx\":65536",
                        "\"num_predict\":2048",
                        "\"format\":{\"type\":\"object\"}",
                        "summary system prompt TARGET_RENDERED_TOKENS=256",
                        "source data")
                .doesNotContain("\"tools\"", "\"model\":\"mistral\"");
    }

    @Test
    void removesUntrustedProviderCauseAndBodyFromTheStableFailure() {
        responseStatus.set(500);
        responseBody.set(SECRET_MARKER);

        assertThatThrownBy(
                        () ->
                                adapter.summarize(
                                        new ContextSummaryRequest(
                                                "summary system prompt",
                                                "source data",
                                                "{\"type\":\"object\"}",
                                                256,
                                                2_048,
                                                4_096)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ollama context summary request failed")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain(SECRET_MARKER));
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
