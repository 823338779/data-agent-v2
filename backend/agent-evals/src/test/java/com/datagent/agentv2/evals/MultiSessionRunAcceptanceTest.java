package com.datagent.agentv2.evals;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 仅通过已启动双实例的公开 HTTP 契约执行多会话验收。 */
@EnabledIfSystemProperty(named = "data.agent.acceptance.enabled", matches = "true")
class MultiSessionRunAcceptanceTest {

    private static final String INSTANCE_A_PROPERTY = "data.agent.acceptance.instance-a";
    private static final String INSTANCE_B_PROPERTY = "data.agent.acceptance.instance-b";
    private static final String DEFAULT_INSTANCE_A = "http://127.0.0.1:8080";
    private static final String DEFAULT_INSTANCE_B = "http://127.0.0.1:8081";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String SESSIONS_PATH = "/api/v1/agent-sessions";
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void fourSessionsRespectUserConcurrencyAcrossInstances() throws Exception {
        URI instanceA = URI.create(property(INSTANCE_A_PROPERTY, DEFAULT_INSTANCE_A));
        URI instanceB = URI.create(property(INSTANCE_B_PROPERTY, DEFAULT_INSTANCE_B));
        // 四个独立用户操作通过实例 B 入队，随后从实例 A 的公开列表观察共享状态。
        for (int index = 1; index <= 4; index++) {
            String sessionId = createSession(instanceB, "双实例验收 " + index);
            submitMessage(instanceB, sessionId, "执行多轮分析场景 " + index);
        }

        JsonNode sessions = await(instanceA, this::hasFourStartedSessions);
        long running = countStatuses(sessions, "running");
        long queued = countStatuses(sessions, "queued");
        assertTrue(running <= 3, "同一用户最多只能有三个 running 执行");
        assertTrue(running + queued >= 4, "四个会话必须都已进入执行生命周期");
    }

    private boolean hasFourStartedSessions(JsonNode sessions) {
        return sessions.isArray()
                && sessions.size() >= 4
                && countStatuses(sessions, "running") + countStatuses(sessions, "queued") >= 4;
    }

    private long countStatuses(JsonNode sessions, String expected) {
        long count = 0;
        for (JsonNode session : sessions) {
            if (expected.equals(session.path("status").asText())) {
                count++;
            }
        }
        return count;
    }

    private JsonNode await(URI baseUri, Predicate<JsonNode> condition) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        JsonNode latest = json.createArrayNode();
        while (Instant.now().isBefore(deadline)) {
            latest = get(baseUri.resolve(SESSIONS_PATH));
            if (condition.test(latest)) {
                return latest;
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("等待公开会话状态收敛超时: " + latest);
    }

    private String createSession(URI baseUri, String title) throws Exception {
        return post(
                        baseUri.resolve(SESSIONS_PATH),
                        Map.of("title", title, "tag", "acceptance"),
                        null)
                .path("conversationSessionId")
                .asText();
    }

    private void submitMessage(URI baseUri, String sessionId, String content) throws Exception {
        post(
                baseUri.resolve(SESSIONS_PATH + "/" + sessionId + "/messages"),
                Map.of("content", content),
                UUID.randomUUID().toString());
    }

    private JsonNode get(URI uri) throws Exception {
        return send(HttpRequest.newBuilder(uri).GET().timeout(TIMEOUT).build());
    }

    private JsonNode post(URI uri, Map<String, ?> body, String idempotencyKey) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header(CONTENT_TYPE, APPLICATION_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_KEY, idempotencyKey);
        }
        return send(request.build());
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(
                response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + " from " + request.uri() + ": " + response.body());
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    private String property(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }
}
