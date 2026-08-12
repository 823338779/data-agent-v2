package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.AgentV2Application;
import com.datagent.agentv2.context.lsm.configuration.LsmContextProperties;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import com.datagent.agentv2.platform.context.TenantContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用真实 PostgreSQL 验收租户隔离和 raw 插件移除路径。 */
class LsmContextCompactionMultiTenantIntegrationTest extends LsmContextPostgresIntegrationSupport {

    private static final String TENANT_A = "tenant_context_a";
    private static final String TENANT_B = "tenant_context_b";
    private static final String USER_A = "user_context_a";
    private static final String USER_B = "user_context_b";
    private static final String SESSION_A = "session_context_a";
    private static final String SESSION_B = "session_context_b";
    private static final String SEGMENT_ID_B = "segment_context_b";
    private static final String SEGMENT_KEY_B =
            "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String CONTENT_HASH =
            "3333333333333333333333333333333333333333333333333333333333333333";
    private static final String MANIFEST_ID_B = "manifest_context_b";
    private static final String EMPTY_ARRAY = "[]";
    private static final String EMPTY_OBJECT = "{}";
    private static final String RAW_SOURCE_IDS = "[\"raw-1\"]";
    private static final String STANDARD_SEGMENT_KIND = "STANDARD";
    private static final String TEST_MODEL = "test";
    private static final String TEST_OPERATOR = "test";
    private static final String UNUSED_PASSWORD = "unused";
    private static final String SESSION_TYPE = "chat";
    private static final String TENANT_A_TITLE = "Tenant A";
    private static final String TENANT_B_TITLE = "Tenant B";
    private static final String STRATEGY_PROPERTY = "data-agent.context.strategy";
    private static final String STRATEGY_RAW = "raw";
    private static final String LSM_FLYWAY_BEAN = "lsmContextFlyway";
    private static final String DATABASE_PREFIX = "context_raw_acceptance_";
    private static final String TRUNCATE_TENANT_SQL = "truncate table tenant cascade";
    private static final String INSERT_TENANT_SQL =
            "insert into tenant (tenant_id, name) values (?, ?)";
    private static final String INSERT_USER_SQL =
            "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?,"
                    + " ?, ?)";
    private static final String INSERT_SESSION_SQL =
            "insert into conversation_session (conversation_session_id, tenant_id, title,"
                    + " session_type, created_by_user_id) values (?, ?, ?, ?, ?)";
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmContextManifestRepository manifests;
    @Autowired private LsmSummarySegmentRepository segments;

    @BeforeEach
    void seedTenants() {
        jdbc.execute(TRUNCATE_TENANT_SQL);
        jdbc.update(INSERT_TENANT_SQL, TENANT_A, TENANT_A);
        jdbc.update(INSERT_TENANT_SQL, TENANT_B, TENANT_B);
        jdbc.update(INSERT_USER_SQL, USER_A, TENANT_A, USER_A, UNUSED_PASSWORD);
        jdbc.update(INSERT_USER_SQL, USER_B, TENANT_B, USER_B, UNUSED_PASSWORD);
        jdbc.update(INSERT_SESSION_SQL, SESSION_A, TENANT_A, TENANT_A_TITLE, SESSION_TYPE, USER_A);
        jdbc.update(INSERT_SESSION_SQL, SESSION_B, TENANT_B, TENANT_B_TITLE, SESSION_TYPE, USER_B);
    }

    @Test
    void tenantCannotReadAnotherTenantsManifestOrSegments() {
        TenantContext.run(
                TENANT_B,
                () -> {
                    segments.findOrCreate(segmentB());
                    manifests.findOrCreate(manifestB());
                });

        assertThat(TenantContext.call(TENANT_B, () -> manifests.findManifest(SESSION_B)))
                .isPresent();
        assertThat(TenantContext.call(TENANT_B, () -> segments.findBySession(SESSION_B)))
                .hasSize(1);
        assertThat(
                        TenantContext.call(
                                TENANT_B,
                                () -> segments.findBySegmentKey(SESSION_B, SEGMENT_KEY_B)))
                .isPresent();

        assertThat(TenantContext.call(TENANT_A, () -> manifests.findManifest(SESSION_B))).isEmpty();
        assertThat(TenantContext.call(TENANT_A, () -> segments.findBySession(SESSION_B))).isEmpty();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () -> segments.findBySegmentKey(SESSION_B, SEGMENT_KEY_B)))
                .isEmpty();
    }

    @Test
    void rawStrategyStartsWithoutCreatingOrAccessingPluginTables() throws Exception {
        String databaseName = createDatabase();
        try (ConfigurableApplicationContext context = startRawContext(databaseName)) {
            ConversationContextStrategyResolver resolver =
                    context.getBean(ConversationContextStrategyResolver.class);

            assertThat(resolver.selected()).isInstanceOf(RawConversationContextStrategy.class);
            assertThat(context.containsBean(LSM_FLYWAY_BEAN)).isFalse();
            assertThat(context.getBeansOfType(LsmConversationContextStrategy.class)).isEmpty();
            assertThat(context.getBeansOfType(LsmContextProperties.class)).isEmpty();
            assertThat(tableExists(context, "flyway_agent_context_lsm_schema_history")).isFalse();
            assertThat(tableExists(context, "conversation_context_lsm_segment")).isFalse();
            assertThat(tableExists(context, "conversation_context_lsm_manifest")).isFalse();
            assertThat(tableExists(context, "conversation_context_lsm_job")).isFalse();
            assertThat(tableExists(context, "conversation_context_lsm_execution_policy")).isFalse();
        }
    }

    private static LsmSummarySegmentEntity segmentB() {
        LocalDateTime now = LocalDateTime.now();
        LsmSummarySegmentEntity segment = new LsmSummarySegmentEntity();
        segment.setSummarySegmentId(SEGMENT_ID_B);
        segment.setConversationSessionId(SESSION_B);
        segment.setSegmentKey(SEGMENT_KEY_B);
        segment.setKind(STANDARD_SEGMENT_KIND);
        segment.setLevel(0);
        segment.setStartTurnSequence(1L);
        segment.setEndTurnSequence(1L);
        segment.setStartMessageSequence(1L);
        segment.setEndMessageSequence(2L);
        segment.setPartialSegment(false);
        segment.setSummaryDocument(EMPTY_OBJECT);
        segment.setEstimatedTokenCount(10);
        segment.setSourceSegmentIds(RAW_SOURCE_IDS);
        segment.setSourceContentSha256(CONTENT_HASH);
        segment.setSummarySchemaVersion(1);
        segment.setPromptVersion(1);
        segment.setModelProvider(TEST_MODEL);
        segment.setModelName(TEST_MODEL);
        segment.setCreationPolicy(EMPTY_OBJECT);
        segment.setSourceSegmentCount(1);
        segment.setCreatedAt(now);
        segment.setCreatedBy(TEST_OPERATOR);
        segment.setUpdatedAt(now);
        segment.setUpdatedBy(TEST_OPERATOR);
        segment.setDeleted(false);
        segment.setVersion(0L);
        return segment;
    }

    private static LsmContextManifestEntity manifestB() {
        LocalDateTime now = LocalDateTime.now();
        LsmContextManifestEntity manifest = new LsmContextManifestEntity();
        manifest.setContextManifestId(MANIFEST_ID_B);
        manifest.setConversationSessionId(SESSION_B);
        manifest.setActiveSegmentIds(EMPTY_ARRAY);
        manifest.setWarmCutoffTurnSequence(0L);
        manifest.setHotFromTurnSequence(0L);
        manifest.setEvaluatedUpToMessageSequence(0L);
        manifest.setEffectivePolicyVersion(1);
        manifest.setEffectiveFanout(5);
        manifest.setEffectiveMaxLevel(4);
        manifest.setGeneration(0L);
        manifest.setCreatedAt(now);
        manifest.setCreatedBy(TEST_OPERATOR);
        manifest.setUpdatedAt(now);
        manifest.setUpdatedBy(TEST_OPERATOR);
        manifest.setDeleted(false);
        manifest.setVersion(0L);
        return manifest;
    }

    private static ConfigurableApplicationContext startRawContext(String databaseName) {
        SpringApplication application = new SpringApplication(AgentV2Application.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.datasource.url=" + jdbcUrl(databaseName));
        arguments.add("--spring.datasource.username=" + POSTGRES.getUsername());
        arguments.add("--spring.datasource.password=" + POSTGRES.getPassword());
        arguments.add("--spring.main.banner-mode=off");
        arguments.add("--server.port=0");
        arguments.add("--data-agent.runtime.scheduling-enabled=false");
        arguments.add("--data-agent.auth.bootstrap.enabled=false");
        arguments.add("--" + STRATEGY_PROPERTY + "=" + STRATEGY_RAW);
        return application.run(arguments.toArray(String[]::new));
    }

    private static String createDatabase() throws Exception {
        String databaseName = DATABASE_PREFIX + DATABASE_SEQUENCE.incrementAndGet();
        // 数据库名仅由固定前缀和本地递增序号组成，不拼接外部输入。
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        return databaseName;
    }

    private static String jdbcUrl(String databaseName) {
        String baseUrl = POSTGRES.getJdbcUrl();
        int separator = baseUrl.lastIndexOf('/');
        return baseUrl.substring(0, separator + 1) + databaseName;
    }

    private static boolean tableExists(ConfigurableApplicationContext context, String tableName)
            throws Exception {
        javax.sql.DataSource dataSource = context.getBean(javax.sql.DataSource.class);
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData().getTables(null, "public", tableName, null)) {
            return tables.next();
        }
    }
}
