package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpCompactEvalGatewayTest {

    private static final String SESSIONS_PATH = "/api/v1/agent-sessions";
    private static final String MESSAGE_PATH = "/api/v1/agent-sessions/session-1/messages";
    private static final String SESSION_PATH = "/api/v1/agent-sessions/session-1";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesPublicSessionContractAndReturnsCurrentExecutionAnswer() throws Exception {
        AtomicInteger sessionReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(SESSIONS_PATH, exchange -> sessions(exchange, sessionReads));
        server.start();

        // 测试服务完整覆盖建会话、提交消息和终态轮询三个公开契约边界。
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        HttpCompactEvalGateway gateway = new HttpCompactEvalGateway(baseUri, new ObjectMapper());
        String sessionId = gateway.createSession("test");
        CompactEvalGateway.TurnResult result =
                gateway.submitAndAwait(sessionId, "question", Duration.ofSeconds(3));

        assertEquals("session-1", sessionId);
        assertEquals("execution-1", result.executionId());
        assertEquals("done", result.status());
        assertEquals("current answer", result.assistantAnswer());
        assertEquals(1, result.compactionCompletedCount());
    }

    /** 单一前缀上下文按照 HTTP 方法和精确路径模拟会话控制器。 */
    private void sessions(HttpExchange exchange, AtomicInteger sessionReads) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (SESSIONS_PATH.equals(path) && "POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 201, "{\"conversationSessionId\":\"session-1\"}");
            return;
        }
        if (MESSAGE_PATH.equals(path) && "POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 202, snapshot("queued", false));
            return;
        }
        if (SESSION_PATH.equals(path) && "GET".equals(exchange.getRequestMethod())) {
            boolean done = sessionReads.incrementAndGet() > 1;
            respond(exchange, 200, snapshot(done ? "done" : "running", done));
            return;
        }
        respond(exchange, 404, "{}");
    }

    private String snapshot(String status, boolean includeAnswer) {
        String messages =
                includeAnswer
                        ? "[{\"role\":\"assistant\",\"executionId\":\"execution-1\",\"content\":\"current answer\"}]"
                        : "[]";
        return "{\"execution\":{\"executionId\":\"execution-1\",\"status\":\""
                + status
                + "\",\"events\":"
                + (includeAnswer ? "[{\"type\":\"context_compaction_completed\"}]" : "[]")
                + "},\"messages\":"
                + messages
                + "}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", JSON_CONTENT_TYPE);
        boolean bodyAllowed = status != 204;
        exchange.sendResponseHeaders(status, bodyAllowed ? bytes.length : -1L);
        if (bodyAllowed) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
