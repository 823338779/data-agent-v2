package com.datagent.agentv2.context.lsm.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 PostgreSQL、Flyway 和 MyBatis Mapper 验证插件 repository 的持久化语义。 */
@SpringBootTest(
        classes = LsmRepositoryIntegrationTest.Application.class,
        properties = "spring.flyway.enabled=false")
class LsmRepositoryIntegrationTest {

    private static final String TENANT_A = "tenant_a";
    private static final String TENANT_B = "tenant_b";
    private static final String SESSION_ID = "session_shared";
    private static final String USER_A = "user_a";
    private static final String SEGMENT_KEY =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String CONTENT_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String EMPTY_ARRAY_JSON = "[]";
    private static final String EMPTY_OBJECT_JSON = "{}";
    private static final String STANDARD_KIND = "STANDARD";
    private static final String SYSTEM_OPERATOR = "lsm-test";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String LSM_HISTORY = "flyway_agent_context_lsm_schema_history";
    private static final String PRIMARY_MIGRATION_LOCATION = "classpath:db/migration";
    private static final String LSM_MIGRATION_LOCATION = "classpath:db/agent-context-lsm/migration";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final String INSERT_TENANT_SQL =
            "insert into tenant (tenant_id, name) values (?, ?)";
    private static final String INSERT_USER_SQL =
            "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?, ?, ?)";
    private static final String INSERT_SESSION_SQL =
            "insert into conversation_session (conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, ?, ?, ?, ?)";
    private static final String INSERT_MANIFEST_SQL =
            "insert into conversation_context_lsm_manifest (context_manifest_id, tenant_id, conversation_session_id, active_segment_ids, warm_cutoff_turn_sequence, hot_from_turn_sequence, evaluated_up_to_message_sequence, effective_policy_version, effective_fanout, effective_max_level, generation, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?::jsonb, 0, 0, 0, 1, 3, 1, ?, ?, ?, ?, ?, false, ?)";
    private static final String COUNT_SEGMENTS_SQL =
            "select count(*) from conversation_context_lsm_segment where tenant_id = ? and conversation_session_id = ? and segment_key = ?";
    private static final String SESSION_TITLE = "LSM integration session";
    private static final String SESSION_TYPE = "chat";
    private static final String PASSWORD_HASH = "not-used";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway(PRIMARY_MIGRATION_LOCATION, PRIMARY_HISTORY).migrate();
        flyway(LSM_MIGRATION_LOCATION, LSM_HISTORY).migrate();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmSummarySegmentRepository segments;
    @Autowired private LsmContextManifestRepository manifests;
    @Autowired private LsmExecutionPolicyRepository executionPolicies;

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

    @BeforeEach
    void seedSessions() {
        jdbc.execute("truncate table tenant cascade");
        jdbc.update(INSERT_TENANT_SQL, TENANT_A, "Tenant A");
        jdbc.update(INSERT_TENANT_SQL, TENANT_B, "Tenant B");
        jdbc.update(INSERT_USER_SQL, USER_A, TENANT_A, USER_A, PASSWORD_HASH);
        jdbc.update(INSERT_SESSION_SQL, SESSION_ID, TENANT_A, SESSION_TITLE, SESSION_TYPE, USER_A);
    }

    @Test
    void springConstructsExecutionPolicyRepositoryWithItsRuntimeDependencies() {
        assertThat(executionPolicies).isNotNull();
    }

    @Test
    void segmentReadsAreTenantIsolatedAndSegmentKeyWritesAreIdempotent() {
        LsmSummarySegmentEntity first = segment("segment_first");
        LsmSummarySegmentEntity duplicate = segment("segment_duplicate");

        LsmSummarySegmentEntity persisted =
                TenantContext.call(TENANT_A, () -> segments.findOrCreate(first));
        LsmSummarySegmentEntity repeated =
                TenantContext.call(TENANT_A, () -> segments.findOrCreate(duplicate));

        assertThat(persisted.getSummarySegmentId()).isEqualTo(first.getSummarySegmentId());
        assertThat(repeated.getSummarySegmentId()).isEqualTo(first.getSummarySegmentId());
        assertThat(
                        TenantContext.call(
                                TENANT_A, () -> segments.findBySegmentKey(SESSION_ID, SEGMENT_KEY)))
                .hasValueSatisfying(
                        segment ->
                                assertThat(segment.getSummarySegmentId())
                                        .isEqualTo(first.getSummarySegmentId()));
        assertThat(
                        TenantContext.call(
                                TENANT_B, () -> segments.findBySegmentKey(SESSION_ID, SEGMENT_KEY)))
                .isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                COUNT_SEGMENTS_SQL,
                                Integer.class,
                                TENANT_A,
                                SESSION_ID,
                                SEGMENT_KEY))
                .isEqualTo(1);
    }

    @Test
    void databaseAllowsOnlyOneManifestForTenantSessionAndRejectsStaleCas() {
        insertManifest("manifest_current", 7L, 3L);

        assertThatThrownBy(() -> insertManifest("manifest_duplicate", 0L, 0L))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        LsmContextManifestEntity replacement = manifest(4L);
        boolean replaced =
                TenantContext.call(
                        TENANT_A,
                        () -> manifests.compareAndSetManifest(SESSION_ID, 6L, 3L, replacement));

        assertThat(replaced).isFalse();
        assertThat(TenantContext.call(TENANT_A, () -> manifests.findManifest(SESSION_ID)))
                .hasValueSatisfying(
                        manifest -> {
                            assertThat(manifest.getVersion()).isEqualTo(7L);
                            assertThat(manifest.getGeneration()).isEqualTo(3L);
                        });
        assertThat(TenantContext.call(TENANT_B, () -> manifests.findManifest(SESSION_ID)))
                .isEmpty();
    }

    private void insertManifest(String manifestId, long version, long generation) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                INSERT_MANIFEST_SQL,
                manifestId,
                TENANT_A,
                SESSION_ID,
                EMPTY_ARRAY_JSON,
                generation,
                now,
                SYSTEM_OPERATOR,
                now,
                SYSTEM_OPERATOR,
                version);
    }

    private static LsmSummarySegmentEntity segment(String segmentId) {
        LocalDateTime now = LocalDateTime.now();
        LsmSummarySegmentEntity segment = new LsmSummarySegmentEntity();
        segment.setSummarySegmentId(segmentId);
        segment.setConversationSessionId(SESSION_ID);
        segment.setSegmentKey(SEGMENT_KEY);
        segment.setKind(STANDARD_KIND);
        segment.setLevel(0);
        segment.setStartTurnSequence(1L);
        segment.setEndTurnSequence(1L);
        segment.setStartMessageSequence(1L);
        segment.setEndMessageSequence(1L);
        segment.setPartialSegment(false);
        segment.setSummaryDocument(EMPTY_OBJECT_JSON);
        segment.setEstimatedTokenCount(0);
        segment.setSourceSegmentIds(EMPTY_ARRAY_JSON);
        segment.setSourceContentSha256(CONTENT_HASH);
        segment.setSummarySchemaVersion(1);
        segment.setPromptVersion(1);
        segment.setModelProvider(SYSTEM_OPERATOR);
        segment.setModelName(SYSTEM_OPERATOR);
        segment.setCreationPolicy(EMPTY_OBJECT_JSON);
        segment.setSourceSegmentCount(0);
        segment.setCreatedAt(now);
        segment.setCreatedBy(SYSTEM_OPERATOR);
        segment.setUpdatedAt(now);
        segment.setUpdatedBy(SYSTEM_OPERATOR);
        segment.setDeleted(false);
        segment.setVersion(0L);
        return segment;
    }

    private static LsmContextManifestEntity manifest(long generation) {
        LocalDateTime now = LocalDateTime.now();
        LsmContextManifestEntity manifest = new LsmContextManifestEntity();
        manifest.setActiveSegmentIds(EMPTY_ARRAY_JSON);
        manifest.setWarmCutoffTurnSequence(0L);
        manifest.setHotFromTurnSequence(0L);
        manifest.setEvaluatedUpToMessageSequence(0L);
        manifest.setEffectivePolicyVersion(1);
        manifest.setEffectiveFanout(3);
        manifest.setEffectiveMaxLevel(1);
        manifest.setGeneration(generation);
        manifest.setUpdatedAt(now);
        manifest.setUpdatedBy(SYSTEM_OPERATOR);
        return manifest;
    }

    private static Flyway flyway(String location, String historyTable) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion(INITIAL_BASELINE_VERSION)
                .load();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        LsmMapperConfiguration.class,
        LsmSummarySegmentRepository.class,
        LsmContextManifestRepository.class,
        LsmExecutionPolicyRepository.class,
        LsmDocumentCodec.class,
        InfrastructureConfiguration.class
    })
    static class Application {}

    @TestConfiguration(proxyBeanMethods = false)
    static class InfrastructureConfiguration {

        @Bean
        IdGenerator idGenerator() {
            return new PrefixedUuidIdGenerator();
        }
    }
}
