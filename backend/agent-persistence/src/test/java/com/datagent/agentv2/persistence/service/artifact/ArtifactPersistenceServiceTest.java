package com.datagent.agentv2.persistence.service.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = AgentPersistenceTestApplication.class)
@Import({ConversationMessageArtifactService.class, MessageUiBlockService.class})
class ArtifactPersistenceServiceTest {

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
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConversationMessageArtifactService messageArtifacts;
    @Autowired private MessageUiBlockService uiBlocks;

    @BeforeEach
    void seedArtifactRelations() {
        jdbc.execute("truncate table tenant cascade");
        jdbc.update("insert into tenant (tenant_id, name) values ('tenant_local', 'Local')");
        jdbc.update(
                "insert into conversation_session "
                        + "(conversation_session_id, tenant_id, title) values ('session-1', 'tenant_local', 'Artifacts')");
        jdbc.update(
                "insert into conversation_message "
                        + "(conversation_message_id, tenant_id, conversation_session_id, role, content, conversation_message_sequence) "
                        + "values ('user-1', 'tenant_local', 'session-1', 'user', 'create', 1)");
        jdbc.update(
                "insert into agent_execution "
                        + "(execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, "
                        + "request_idempotency_key, request_payload_hash, hard_deadline_at) "
                        + "values ('execution-1', 'tenant_local', 'session-1', 'user-1', 'running', 'key-1', "
                        + "repeat('a', 64), current_timestamp + interval '1 hour')");
        jdbc.update(
                "insert into conversation_message "
                        + "(conversation_message_id, tenant_id, conversation_session_id, execution_id, role, content, conversation_message_sequence) "
                        + "values ('message-1', 'tenant_local', 'session-1', 'execution-1', 'assistant', '', 2)");
        jdbc.update(
                "insert into artifact_build_job "
                        + "(artifact_build_job_id, tenant_id, conversation_session_id, execution_id, conversation_message_id, "
                        + "artifact_type, operation, status, specification, template_id, template_version, skill_digest, "
                        + "script_digest, runtime_version, platform) values "
                        + "('job-1', 'tenant_local', 'session-1', 'execution-1', 'message-1', 'DOCX', 'CREATE', "
                        + "'SUCCEEDED', '{}'::jsonb, 'blank', '1', repeat('b', 64), repeat('c', 64), 'test', 'WINDOWS')");
        jdbc.update(
                "insert into artifact_object "
                        + "(artifact_id, tenant_id, conversation_session_id, artifact_build_job_id, specification_version, "
                        + "artifact_type, status, display_name, media_type, storage_key, size_bytes, sha256) values "
                        + "('artifact-1', 'tenant_local', 'session-1', 'job-1', 1, 'DOCX', 'READY', 'report.docx', "
                        + "'application/vnd.openxmlformats-officedocument.wordprocessingml.document', "
                        + "'ready/artifact-1/output.docx', 12, repeat('d', 64))");
        jdbc.update(
                "insert into conversation_message_artifact "
                        + "(conversation_message_artifact_id, tenant_id, conversation_message_id, artifact_id, display_order) "
                        + "values ('relation-1', 'tenant_local', 'message-1', 'artifact-1', 0)");
        jdbc.update(
                "insert into message_ui_block "
                        + "(ui_block_id, tenant_id, conversation_message_id, type, schema_version, variant, payload, display_order) "
                        + "values ('block-1', 'tenant_local', 'message-1', 'artifact-card', 1, 'default', "
                        + "'{\"artifactId\":\"artifact-1\"}'::jsonb, 0)");
    }

    @Test
    void batchQueriesReturnRelationsInStableDisplayOrder() {
        TenantContext.run(
                "tenant_local",
                () -> {
                    assertThat(messageArtifacts.listByMessageIds(List.of("message-1", "missing")))
                            .extracting(entity -> entity.getArtifactId())
                            .containsExactly("artifact-1");
                    assertThat(uiBlocks.listByMessageIds(List.of("message-1", "missing")))
                            .extracting(entity -> entity.getUiBlockId())
                            .containsExactly("block-1");
                });
    }

    @Test
    void emptyBatchQueriesDoNotRequireTenantContext() {
        assertThat(messageArtifacts.listByMessageIds(List.of())).isEmpty();
        assertThat(uiBlocks.listByMessageIds(List.of())).isEmpty();
    }
}
