package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 通过完整 HTTP 受理验证目标摘要、原子绑定与同键冲突。 */
@SpringBootTest
@AutoConfigureMockMvc
class GitExecutionWorkspaceIntegrationTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final String SESSIONS = "/api/v1/agent-sessions";
    private static final String MESSAGES = SESSIONS + "/{session}/messages";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String KEY = "workspace-request";
    private static final String A = "wa";
    private static final String FIRST = "{\"content\":\"开发指标\",\"workspaceId\":\"wa\"}";
    private static final String SECOND = "{\"content\":\"开发指标\",\"workspaceId\":\"wb\"}";
    private static final String WORKSPACES =
            """
            insert into git_workspace(workspace_id,conversation_session_id,repository_id,status,project_id,branch_id,git_ref)
            values ('wa',?,'repo','READY','p','a','refs/heads/a'),
                   ('wb',?,'repo','READY','p','b','refs/heads/b')
            """;
    private static final String LINKS =
            """
            insert into git_session_workspace(conversation_session_id,workspace_id) values (?,'wa'),(?,'wb')
            """;
    private static final String BINDING =
            "select workspace_id from git_execution_workspace where execution_id=?";
    private static final String COUNT =
            "select count(*) from agent_execution where conversation_session_id=?";
    private static final String SESSION_ID = "conversationSessionId";
    private static final String EXECUTION = "execution";
    private static final String EXECUTION_ID = "executionId";

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoBean private ReactExecutionEngine executionEngine;

    @Test
    void sameKeyDifferentWorkspaceIsConflictAndOriginalBindingSurvives() throws Exception {
        String created =
                mvc.perform(
                                post(SESSIONS)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"工作区受理\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String session = json.readTree(created).path(SESSION_ID).asString();
        jdbc.update(WORKSPACES, session, session);
        jdbc.update(LINKS, session, session);
        String accepted =
                mvc.perform(
                                post(MESSAGES, session)
                                        .header(KEY_HEADER, KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(FIRST))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String execution = json.readTree(accepted).path(EXECUTION).path(EXECUTION_ID).asString();
        mvc.perform(
                        post(MESSAGES, session)
                                .header(KEY_HEADER, KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SECOND))
                .andExpect(status().isConflict());
        String replay =
                mvc.perform(
                                post(MESSAGES, session)
                                        .header(KEY_HEADER, KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(FIRST))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(json.readTree(replay).path(EXECUTION).path(EXECUTION_ID).asString())
                .isEqualTo(execution);
        assertThat(jdbc.queryForObject(BINDING, String.class, execution)).isEqualTo(A);
        assertThat(jdbc.queryForObject(COUNT, Integer.class, session)).isEqualTo(1);
    }
}
