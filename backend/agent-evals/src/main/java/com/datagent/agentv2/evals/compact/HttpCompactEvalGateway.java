package com.datagent.agentv2.evals.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 仅通过 Data Agent V2 公开 HTTP 契约执行长对话回放。 */
public final class HttpCompactEvalGateway implements CompactEvalGateway {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String SESSIONS_PATH = "/api/v1/agent-sessions";
    private static final String MESSAGE_PATH_SUFFIX = "/messages";
    private static final String EVAL_TAG = "context-compact-eval";
    private static final String TITLE_FIELD = "title";
    private static final String TAG_FIELD = "tag";
    private static final String USERNAME_FIELD = "username";
    private static final String PASSWORD_FIELD = "password";
    private static final String CONTENT_FIELD = "content";
    private static final String SESSION_ID_FIELD = "conversationSessionId";
    private static final String EXECUTION_FIELD = "execution";
    private static final String EXECUTION_ID_FIELD = "executionId";
    private static final String STATUS_FIELD = "status";
    private static final String MESSAGES_FIELD = "messages";
    private static final String EVENTS_FIELD = "events";
    private static final String TYPE_FIELD = "type";
    private static final String ROLE_FIELD = "role";
    private static final String ASSISTANT_ROLE = "assistant";
    private static final String COMPACTION_STARTED_EVENT = "context_compaction_started";
    private static final String COMPACTION_COMPLETED_EVENT = "context_compaction_completed";
    private static final String COMPACTION_FAILED_EVENT = "context_compaction_failed";
    private static final String DONE_STATUS = "done";
    private static final Set<String> TERMINAL_STATUSES =
            Set.of(
                    DONE_STATUS,
                    "failed",
                    "cancelled",
                    "lost",
                    "waiting_for_user",
                    "waiting_confirmation");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private final URI baseUri;
    private final ObjectMapper json;
    private final HttpClient http;

    public HttpCompactEvalGateway(URI baseUri, String username, String password, ObjectMapper json)
            throws Exception {
        this.baseUri = baseUri;
        this.json = json;
        this.http =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build();
        login(username, password);
    }

    /** 每个场景创建独立会话，避免不同语料之间共享 Compact 状态。 */
    @Override
    public String createSession(String title) throws Exception {
        JsonNode created =
                post(SESSIONS_PATH, Map.of(TITLE_FIELD, title, TAG_FIELD, EVAL_TAG), null);
        return requiredText(created, SESSION_ID_FIELD);
    }

    /** 提交后按公开查询接口轮询，不读取 Compact 私有表或运行时实现。 */
    @Override
    public TurnResult submitAndAwait(String sessionId, String content, Duration timeout)
            throws Exception {
        Instant startedAt = Instant.now();
        JsonNode submitted =
                post(
                        SESSIONS_PATH + "/" + sessionId + MESSAGE_PATH_SUFFIX,
                        Map.of(CONTENT_FIELD, content),
                        UUID.randomUUID().toString());
        String executionId = requiredText(submitted.path(EXECUTION_FIELD), EXECUTION_ID_FIELD);

        // 同一 execution 的 assistant 消息可能晚于终态事件写入，因此以完整会话快照为准。
        Instant deadline = startedAt.plus(timeout);
        JsonNode latest = submitted;
        while (Instant.now().isBefore(deadline)) {
            latest = get(SESSIONS_PATH + "/" + sessionId);
            String status = text(latest.path(EXECUTION_FIELD), STATUS_FIELD);
            if (status != null && TERMINAL_STATUSES.contains(status)) {
                return result(latest, executionId, status, startedAt);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException(
                "Timed out waiting for execution " + executionId + ": " + latest);
    }

    /** 登录 Cookie 由该网关私有持有，避免不同服务实例间串用认证状态。 */
    private void login(String username, String password) throws Exception {
        post(LOGIN_PATH, Map.of(USERNAME_FIELD, username, PASSWORD_FIELD, password), null);
    }

    /** 优先选择当前 execution 的回答，失败场景再退回最新 assistant 正文。 */
    private TurnResult result(
            JsonNode session, String executionId, String status, Instant startedAt) {
        String answer = "";
        for (JsonNode message : session.path(MESSAGES_FIELD)) {
            if (!ASSISTANT_ROLE.equals(text(message, ROLE_FIELD))) {
                continue;
            }
            if (executionId.equals(text(message, EXECUTION_ID_FIELD)) || answer.isBlank()) {
                answer = text(message, CONTENT_FIELD);
            }
        }
        return new TurnResult(
                executionId,
                status,
                answer == null ? "" : answer,
                session.path(MESSAGES_FIELD).size(),
                countEvents(session, COMPACTION_STARTED_EVENT),
                countEvents(session, COMPACTION_COMPLETED_EVENT),
                countEvents(session, COMPACTION_FAILED_EVENT),
                Duration.between(startedAt, Instant.now()).toMillis());
    }

    /** Compact 生命周期是公开 execution 事件，可用于确认长语料是否真正触发压缩。 */
    private int countEvents(JsonNode session, String expectedType) {
        int count = 0;
        for (JsonNode event : session.path(EXECUTION_FIELD).path(EVENTS_FIELD)) {
            if (expectedType.equals(text(event, TYPE_FIELD))) {
                count++;
            }
        }
        return count;
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
        return send(request);
    }

    private JsonNode post(String path, Map<String, ?> body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .timeout(REQUEST_TIMEOUT)
                        .header(CONTENT_TYPE_HEADER, APPLICATION_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return send(request.build());
    }

    /** 所有非 2xx 响应都保留正文，便于从评测日志定位服务配置或契约问题。 */
    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    response.statusCode() + " from " + request.uri() + ": " + response.body());
        }
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    private static String requiredText(JsonNode parent, String field) {
        String value = text(parent, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Response field is missing: " + field);
        }
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
