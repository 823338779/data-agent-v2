package com.datagent.agentv2.auth;

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
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = AgentAuthTestApplication.class)
@AutoConfigureMockMvc
@Import(AuthenticationHttpIntegrationTest.ContextProbeController.class)
class AuthenticationHttpIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("data-agent.auth.bootstrap.enabled", () -> "true");
        registry.add("data-agent.auth.bootstrap.password", () -> "admin");
        registry.add("data-agent.auth.session.secure-cookie", () -> "false");
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearLoginSessions() {
        jdbc.execute("truncate table login_session");
    }

    @Test
    void bootstrapCreatesLocalAdminWithBCryptPassword() {
        var account =
                jdbc.queryForMap(
                        "select id, tenant_id, username, password_hash from user_account where username = 'admin'");

        assertThat(account.get("id")).isEqualTo("user_admin");
        assertThat(account.get("tenant_id")).isEqualTo("tenant_local");
        assertThat(account.get("username")).isEqualTo("admin");
        assertThat(account.get("password_hash")).asString().startsWith("$2");
        assertThat(passwordEncoder.matches("admin", account.get("password_hash").toString()))
                .isTrue();
    }

    @Test
    void loginStoresOnlyTokenHashAndCookieRestoresSession() throws Exception {
        String setCookie = login();
        String token = cookieValue(setCookie);

        assertThat(setCookie)
                .contains("DATA_AGENT_SESSION=")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure");

        var stored = jdbc.queryForMap("select token_hash, expires_at from login_session");
        assertThat(stored.get("token_hash")).isEqualTo(sha256(token));
        assertThat(stored.get("token_hash")).isNotEqualTo(token);

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

        assertThat(
                        jdbc.queryForObject(
                                "select revoked_at is not null from login_session", Boolean.class))
                .isTrue();
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
