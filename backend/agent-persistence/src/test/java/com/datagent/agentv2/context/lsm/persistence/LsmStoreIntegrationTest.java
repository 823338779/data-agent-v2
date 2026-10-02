package com.datagent.agentv2.context.lsm.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.configuration.LsmFlywayMigrator;
import com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import java.time.LocalDateTime;
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
        properties = "data-agent.context.strategy=lsm")
@Import({LsmMapperConfiguration.class, LsmFlywayMigrator.class})
class LsmStoreIntegrationTest {
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
    @Autowired private LsmContextManifestRepository manifests;

    @Test
    void manifestCasRoundTripsJsonAndClearsNullablePointers() {
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into conversation_session(tenant_id,conversation_session_id,title) values ('tenant_local','session','Session')");
        var initial = manifest();
        manifests.findOrCreate(initial);
        assertThat(manifests.findOrCreate(initial).getVersion()).isZero();
        var first = manifest();
        first.setGeneration(1L);
        first.setActiveRootSegmentId("root");
        first.setActiveSegmentIds("[\"segment\"]");
        first.setPendingRawStartTurnSequence(1L);
        first.setPendingRawEndTurnSequence(2L);
        assertThat(manifests.compareAndSetManifest("session", 0, 0, first)).isTrue();
        var loaded = manifests.findManifest("session").orElseThrow();
        assertThat(loaded.getActiveSegmentIds()).contains("segment");
        assertThat(loaded.getActiveRootSegmentId()).isEqualTo("root");
        assertThat(manifests.compareAndSetManifest("session", 0, 0, first)).isFalse();
        var cleared = manifest();
        cleared.setGeneration(2L);
        assertThat(manifests.compareAndSetManifest("session", 1, 1, cleared)).isTrue();
        loaded = manifests.findManifest("session").orElseThrow();
        assertThat(loaded.getActiveRootSegmentId()).isNull();
        assertThat(loaded.getPendingRawStartTurnSequence()).isNull();
        assertThat(loaded.getVersion()).isEqualTo(2L);
    }

    private static LsmContextManifestEntity manifest() {
        var result = new LsmContextManifestEntity();
        result.setContextManifestId("manifest");
        result.setConversationSessionId("session");
        result.setActiveSegmentIds("[]");
        result.setHotFromTurnSequence(0L);
        result.setEvaluatedUpToMessageSequence(0L);
        result.setEffectivePolicyVersion(1);
        result.setEffectiveFanout(3);
        result.setEffectiveMaxLevel(2);
        result.setGeneration(0L);
        result.setVersion(0L);
        result.setCreatedAt(LocalDateTime.now());
        result.setUpdatedAt(LocalDateTime.now());
        result.setCreatedBy("test");
        result.setUpdatedBy("test");
        return result;
    }
}
