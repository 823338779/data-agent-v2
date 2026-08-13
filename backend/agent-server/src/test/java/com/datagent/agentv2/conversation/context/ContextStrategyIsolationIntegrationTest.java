package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.AgentV2Application;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验收 raw、LSM 与 hierarchical 三种可部署策略的互斥装配和失败门禁。 */
class ContextStrategyIsolationIntegrationTest {

    private static final String STRATEGY_PROPERTY = "data-agent.context.strategy";
    private static final String STRATEGY_RAW = "raw";
    private static final String STRATEGY_LSM = "lsm";
    private static final String STRATEGY_HIERARCHICAL = "hierarchical";
    private static final String STRATEGY_UNKNOWN = "unknown";
    private static final String LSM_HISTORY = "flyway_agent_context_lsm_schema_history";
    private static final String LSM_TABLE = "conversation_context_lsm_manifest";
    private static final String HIERARCHICAL_HISTORY =
            "flyway_agent_context_hierarchical_schema_history";
    private static final String HIERARCHICAL_TABLE = "conversation_context_hierarchical_checkpoint";
    private static final String DATABASE_PREFIX = "context_strategy_isolation_";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void rawSelectsOnlyRawAndRunsNeitherPluginMigration() throws Exception {
        try (ConfigurableApplicationContext context = startContext(STRATEGY_RAW, false)) {
            assertThat(selectedStrategy(context)).isEqualTo(STRATEGY_RAW);
            assertThat(tableExists(context, LSM_HISTORY)).isFalse();
            assertThat(tableExists(context, LSM_TABLE)).isFalse();
            assertThat(tableExists(context, HIERARCHICAL_HISTORY)).isFalse();
            assertThat(tableExists(context, HIERARCHICAL_TABLE)).isFalse();
        }
    }

    @Test
    void lsmSelectsOnlyLsmAndRunsOnlyItsPrivateMigration() throws Exception {
        try (ConfigurableApplicationContext context = startContext(STRATEGY_LSM, false)) {
            assertThat(selectedStrategy(context)).isEqualTo(STRATEGY_LSM);
            assertThat(tableExists(context, LSM_HISTORY)).isTrue();
            assertThat(tableExists(context, LSM_TABLE)).isTrue();
            assertThat(tableExists(context, HIERARCHICAL_HISTORY)).isFalse();
            assertThat(tableExists(context, HIERARCHICAL_TABLE)).isFalse();
        }
    }

    @Test
    void hierarchicalSelectsOnlyHierarchicalAndRunsOnlyItsPrivateMigration() throws Exception {
        try (ConfigurableApplicationContext context = startContext(STRATEGY_HIERARCHICAL, false)) {
            assertThat(selectedStrategy(context)).isEqualTo(STRATEGY_HIERARCHICAL);
            assertThat(tableExists(context, HIERARCHICAL_HISTORY)).isTrue();
            assertThat(tableExists(context, HIERARCHICAL_TABLE)).isTrue();
            assertThat(tableExists(context, LSM_HISTORY)).isFalse();
            assertThat(tableExists(context, LSM_TABLE)).isFalse();
        }
    }

    @Test
    void unknownAndDuplicateStrategyIdsFailFast() {
        assertThatThrownBy(() -> startContext(STRATEGY_UNKNOWN, false))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Unknown context strategy id: " + STRATEGY_UNKNOWN);
        assertThatThrownBy(() -> startContext(STRATEGY_RAW, true))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Duplicate context strategy id: " + STRATEGY_RAW);
    }

    private static String selectedStrategy(ConfigurableApplicationContext context) {
        return context.getBean(ConversationContextStrategyResolver.class).selected().strategyId();
    }

    private static ConfigurableApplicationContext startContext(String strategy, boolean duplicate)
            throws Exception {
        POSTGRES.start();
        String databaseName = DATABASE_PREFIX + DATABASE_SEQUENCE.incrementAndGet();
        // 每个策略使用独立数据库，使迁移表是否存在成为与测试顺序无关的证据。
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        SpringApplication application =
                duplicate
                        ? new SpringApplication(
                                AgentV2Application.class, DuplicateRawStrategyConfiguration.class)
                        : new SpringApplication(AgentV2Application.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.datasource.url=" + jdbcUrl(databaseName));
        arguments.add("--spring.datasource.username=" + POSTGRES.getUsername());
        arguments.add("--spring.datasource.password=" + POSTGRES.getPassword());
        arguments.add("--spring.main.banner-mode=off");
        arguments.add("--server.port=0");
        arguments.add("--data-agent.runtime.scheduling-enabled=false");
        arguments.add("--data-agent.auth.bootstrap.enabled=false");
        arguments.add("--" + STRATEGY_PROPERTY + "=" + strategy);
        return application.run(arguments.toArray(String[]::new));
    }

    private static String jdbcUrl(String databaseName) {
        String baseUrl = POSTGRES.getJdbcUrl();
        return baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + databaseName;
    }

    private static boolean tableExists(ConfigurableApplicationContext context, String tableName)
            throws Exception {
        javax.sql.DataSource dataSource = context.getBean(javax.sql.DataSource.class);
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData().getTables(null, "public", tableName, null)) {
            return tables.next();
        }
    }

    /** 注入第二个 raw ID，只验证 resolver 的 duplicate fail-fast，不承担业务行为。 */
    @Configuration(proxyBeanMethods = false)
    static class DuplicateRawStrategyConfiguration {

        @Bean
        ConversationContextStrategy duplicateRawStrategy() {
            return new ConversationContextStrategy() {
                @Override
                public String strategyId() {
                    return STRATEGY_RAW;
                }

                @Override
                public PreparedConversationContext prepare(ConversationContextRequest request) {
                    return new PreparedConversationContext(STRATEGY_RAW, List.of(), 0, false, 0L);
                }
            };
        }
    }
}
