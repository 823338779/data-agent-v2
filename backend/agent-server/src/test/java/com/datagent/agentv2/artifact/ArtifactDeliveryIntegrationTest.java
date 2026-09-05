package com.datagent.agentv2.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.artifact.application.ArtifactPublicationRequest;
import com.datagent.agentv2.artifact.application.ArtifactPublicationResult;
import com.datagent.agentv2.artifact.application.ArtifactPublicationService;
import com.datagent.agentv2.artifact.domain.ArtifactType;
import com.datagent.agentv2.artifact.storage.ArtifactStorage;
import com.datagent.agentv2.artifact.storage.StagedArtifact;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ArtifactDeliveryIntegrationTest {

    private static final byte[] FIXTURE = {0x50, 0x4b, 0x03, 0x04};
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final Path ARTIFACT_ROOT = createArtifactRoot();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("data-agent.artifact.root-directory", ARTIFACT_ROOT::toString);
    }

    @AfterAll
    static void cleanup() throws IOException {
        POSTGRES.stop();
        try (var paths = Files.walk(ARTIFACT_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ArtifactStorage storage;
    @Autowired private ArtifactPublicationService publication;
    @MockitoBean private ReactExecutionEngine executionEngine;

    @BeforeEach
    void clearData() {
        jdbc.update("delete from message_ui_block");
        jdbc.update("delete from conversation_message_artifact");
        jdbc.update("delete from artifact_object");
        jdbc.update("delete from artifact_build_job");
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");
    }

    @Test
    void publishesProjectsAndDownloadsAnImmutableFixture() throws Exception {
        String sessionId = createSession("artifact-delivery");
        String executionId = submit(sessionId);
        String assistantMessageId =
                jdbc.queryForObject(
                        "select conversation_message_id from conversation_message where execution_id = ? and role = 'assistant'",
                        String.class,
                        executionId);
        insertBuildJob(sessionId, executionId, assistantMessageId);
        StagedArtifact staged = storage.createStaging("job-1", ArtifactType.DOCX);
        storage.write(staged, new ByteArrayInputStream(FIXTURE));
        MvcResult eventStream =
                mvc.perform(
                                get("/api/v1/agent-sessions/{sessionId}/events", sessionId)
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();

        ArtifactPublicationResult result =
                publication.publish(
                        new ArtifactPublicationRequest(
                                "job-1",
                                executionId,
                                ArtifactType.DOCX,
                                "固定报告.docx",
                                json.createObjectNode().put("fixture", true),
                                1,
                                1,
                                staged));

        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            String events = eventStream.getResponse().getContentAsString();
                            assertThat(events).contains("event:message_update");
                            assertThat(events).contains(result.artifactId());
                            assertThat(events).contains(result.uiBlockId());
                        });
        eventStream.getRequest().getAsyncContext().complete();

        mvc.perform(get("/api/v1/agent-sessions/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.messages[1].content")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "<agent-ui block-id=\"")))
                .andExpect(
                        jsonPath("$.messages[1].artifacts[0].artifactId")
                                .value(result.artifactId()))
                .andExpect(jsonPath("$.messages[1].uiBlocks[0].blockId").value(result.uiBlockId()));

        String downloadPath =
                "/api/v1/agent-sessions/"
                        + sessionId
                        + "/artifacts/"
                        + result.artifactId()
                        + "/content";
        MvcResult downloadResponse =
                mvc.perform(get(downloadPath)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(downloadResponse))
                .andExpect(status().isOk())
                .andExpect(content().bytes(FIXTURE))
                .andExpect(header().string("Content-Length", String.valueOf(FIXTURE.length)));

        String otherSession = createSession("other-session");
        mvc.perform(
                        get(
                                "/api/v1/agent-sessions/{sessionId}/artifacts/{artifactId}/content",
                                otherSession,
                                result.artifactId()))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "select octet_length(storage_key) > 0 and size_bytes = ? from artifact_object where artifact_id = ?",
                                Boolean.class,
                                FIXTURE.length,
                                result.artifactId()))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from artifact_object where storage_key like ?",
                                Integer.class,
                                "%" + java.util.Base64.getEncoder().encodeToString(FIXTURE) + "%"))
                .isZero();
    }

    private String createSession(String title) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"" + title + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("conversationSessionId").asString();
    }

    private String submit(String sessionId) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions/{sessionId}/messages", sessionId)
                                        .header("Idempotency-Key", "artifact-delivery-key")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"生成固定文档\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("execution").path("executionId").asString();
    }

    private void insertBuildJob(String sessionId, String executionId, String messageId) {
        jdbc.update(
                """
                insert into artifact_build_job (
                    artifact_build_job_id, tenant_id, conversation_session_id, execution_id,
                    conversation_message_id, artifact_type, operation, status, specification,
                    template_id, template_version, skill_digest, script_digest, runtime_version, platform)
                values (?, 'tenant_local', ?, ?, ?, 'DOCX', 'CREATE', 'SUCCEEDED', '{}'::jsonb,
                    'fixture', '1', ?, ?, 'test', 'WINDOWS')
                """,
                "job-1",
                sessionId,
                executionId,
                messageId,
                "a".repeat(64),
                "b".repeat(64));
    }

    private static Path createArtifactRoot() {
        try {
            return Files.createTempDirectory("data-agent-artifacts-");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
