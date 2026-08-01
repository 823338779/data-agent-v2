package com.datagent.agentv2.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticationHttpIntegrationTest.ContextProbeController.class)
class AuthenticationHttpIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCommand("redis-server", "--requirepass", "redis_test")
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "default");
        registry.add("spring.data.redis.password", () -> "redis_test");
        registry.add("data-agent.auth.bootstrap.enabled", () -> "true");
        registry.add("data-agent.auth.bootstrap.password", () -> "admin");
        registry.add("data-agent.auth.session.secure-cookie", () -> "false");
    }

    @AfterAll
    static void stopDatabase() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void clearLoginSessions() {
        var keys = redis.keys("data-agent-v2:auth:session:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void bootstrapCreatesLocalAdminWithBCryptPassword() {
        var account =
                jdbc.queryForMap(
                        "select user_id, tenant_id, username, password_hash from user_account where username = 'admin'");

        assertThat(account.get("user_id")).isEqualTo("user_admin");
        assertThat(account.get("tenant_id")).isEqualTo("tenant_local");
        assertThat(account.get("username")).isEqualTo("admin");
        assertThat(account.get("password_hash")).asString().startsWith("$2");
        assertThat(passwordEncoder.matches("admin", account.get("password_hash").toString()))
                .isTrue();
    }

    @Test
    void loginStoresOnlyTokenHashInRedisWithTtlAndCookieRestoresSession() throws Exception {
        String setCookie = login();
        String token = cookieValue(setCookie);

        assertThat(setCookie)
                .contains("DATA_AGENT_SESSION=")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure");

        String key = "data-agent-v2:auth:session:" + sha256(token);
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.hasKey("data-agent-v2:auth:session:" + token)).isFalse();
        assertThat(redis.getExpire(key))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofHours(8).toSeconds());

        mvc.perform(get("/api/v1/auth/session").cookie(sessionCookie(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant_local"))
                .andExpect(jsonPath("$.subject").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("TENANT_ADMIN"));
    }

    @Test
    void invalidCredentialsReturnStableProblemDetail() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"))
                .andExpect(
                        jsonPath("$.type").value("urn:data-agent:problem:authentication-required"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void logoutRevokesSharedSessionAndClearsCookie() throws Exception {
        String token = cookieValue(login());

        mvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie(token)))
                .andExpect(status().isNoContent())
                .andExpect(
                        header().string(
                                        HttpHeaders.SET_COOKIE,
                                        org.hamcrest.Matchers.containsString("Max-Age=0")));

        assertThat(redis.hasKey("data-agent-v2:auth:session:" + sha256(token))).isFalse();
        mvc.perform(get("/api/v1/auth/session").cookie(sessionCookie(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void authenticatedRequestEstablishesTenantAndOperatorContexts() throws Exception {
        String token = cookieValue(login());

        mvc.perform(get("/__test/auth-context").cookie(sessionCookie(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant_local"))
                .andExpect(jsonPath("$.userId").value("user_admin"));
    }

    @Test
    void secondMessageWhileExecutionIsActiveReturnsConflict() throws Exception {
        String token = cookieValue(login());
        String sessionJson =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(sessionCookie(token))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"Conflict test\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationSessionId =
                objectMapper.readTree(sessionJson).path("conversationSessionId").asString();

        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(sessionCookie(token))
                                .header("Idempotency-Key", "first-message")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"First message\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(sessionCookie(token))
                                .header("Idempotency-Key", "second-message")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"Second message\"}"))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.type")
                                .value("urn:data-agent:problem:active-execution-conflict"))
                .andExpect(jsonPath("$.code").value("ACTIVE_EXECUTION_CONFLICT"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.errors").isArray());
    }

    private String login() throws Exception {
        return mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);
    }

    private static Cookie sessionCookie(String token) {
        return new Cookie("DATA_AGENT_SESSION", token);
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring("DATA_AGENT_SESSION=".length(), setCookie.indexOf(';'));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @RestController
    static class ContextProbeController {

        @GetMapping("/__test/auth-context")
        Map<String, String> context() {
            var operator = OperatorContext.requiredOperator();
            return Map.of(
                    "tenantId", TenantContext.requiredTenantId(), "userId", operator.userId());
        }
    }
}
