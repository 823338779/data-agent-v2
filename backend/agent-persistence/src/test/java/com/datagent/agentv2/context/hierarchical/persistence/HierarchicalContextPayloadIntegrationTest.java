package com.datagent.agentv2.context.hierarchical.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalFlywayMigrator;
import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalMapperConfiguration;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证迁移后的真实 PostgreSQL 存储边界，包括 JSONB 与旧版本 CAS 拒绝。 */
@SpringBootTest(
        classes = AgentPersistenceTestApplication.class,
        properties = "data-agent.context.strategy=hierarchical")
@Import({HierarchicalMapperConfiguration.class, HierarchicalFlywayMigrator.class})
class HierarchicalContextPayloadIntegrationTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HierarchicalCheckpointRepository checkpoints;
    @Autowired private HierarchicalColdRepository colds;

    @Autowired
    private com.datagent.agentv2.context.hierarchical.persistence.repository
                    .ContextPayloadRepository
            payloads;

    @Test
    void archiveIsIdempotentAndRestrictedToSourceSession() {
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into conversation_session(tenant_id,conversation_session_id,title) values ('tenant_local','payload-session','Payload')");
        var source =
                com.datagent.agentv2.context.hierarchical.persistence.entity.ContextPayloadEntity
                        .create(
                                "payload-ref",
                                "tenant_local",
                                "payload-session",
                                "execution",
                                "process",
                                "OUTPUT",
                                "0".repeat(64),
                                "安全内容");
        var first = payloads.insertOrSelect(source);
        assertThat(payloads.insertOrSelect(source).getReference()).isEqualTo(first.getReference());
        assertThat(first.getContent()).isEqualTo("安全内容");
        assertThat(first.getTotalBytes()).isEqualTo(12);
        assertThat(payloads.findVisible("other-session", "payload-ref")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_payload", Integer.class))
                .isEqualTo(1);
        jdbc.update(
                "delete from conversation_session where conversation_session_id='payload-session'");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_payload", Integer.class))
                .isZero();
    }
}
