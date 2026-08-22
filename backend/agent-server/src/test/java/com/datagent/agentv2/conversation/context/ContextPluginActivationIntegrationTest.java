package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.AgentV2Application;
import com.datagent.agentv2.context.lsm.configuration.LsmContextProperties;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ContextPluginActivationIntegrationTest {

    private static final String STRATEGY_PROPERTY = "data-agent.context.strategy";
    private static final String STRATEGY_RAW = "raw";
    private static final String STRATEGY_LSM = "lsm";
    private static final String STRATEGY_UNKNOWN = "unknown";
    private static final String LSM_FLYWAY_BEAN = "lsmContextFlyway";
    private static final String LSM_HISTORY_TABLE = "flyway_agent_context_lsm_schema_history";
    private static final String LSM_MANIFEST_TABLE = "conversation_context_lsm_manifest";
    private static final String DATABASE_PREFIX = "context_activation_";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void rawStrategyLeavesLsmPluginAndTablesUntouched() throws Exception {
        String databaseName = createDatabase();
        try (ConfigurableApplicationContext context = startContext(databaseName, STRATEGY_RAW)) {
            assertThat(context.containsBean(LSM_FLYWAY_BEAN)).isFalse();
            assertThat(context.getBeansOfType(LsmConversationContextStrategy.class)).isEmpty();
            assertThat(context.getBeansOfType(LsmContextProperties.class)).isEmpty();
            assertThat(tableExists(context, LSM_HISTORY_TABLE)).isFalse();
            assertThat(tableExists(context, LSM_MANIFEST_TABLE)).isFalse();
        }
    }

    @Test
    void lsmStrategyMigratesPluginStoreBeforeSelectingStrategy() throws Exception {
        String databaseName = createDatabase();
        try (ConfigurableApplicationContext context = startContext(databaseName, STRATEGY_LSM)) {
            ConversationContextStrategyResolver resolver =
                    context.getBean(ConversationContextStrategyResolver.class);

            assertThat(context.containsBean(LSM_FLYWAY_BEAN)).isTrue();
            assertThat(context.getBeansOfType(LsmConversationContextStrategy.class)).hasSize(1);
            assertThat(resolver.selected()).isInstanceOf(LsmConversationContextStrategy.class);
            assertThat(tableExists(context, LSM_HISTORY_TABLE)).isTrue();
            assertThat(tableExists(context, LSM_MANIFEST_TABLE)).isTrue();
        }
    }

    @Test
    void unknownStrategyFailsAtStartup() throws Exception {
        String databaseName = createDatabase();

        assertThatThrownBy(() -> startContext(databaseName, STRATEGY_UNKNOWN))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Unknown context strategy id: " + STRATEGY_UNKNOWN);
    }

    private static ConfigurableApplicationContext startContext(
            String databaseName, String strategy) {
        SpringApplication application = new SpringApplication(AgentV2Application.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        List<String> arguments = new ArrayList<>();
        // 每个场景使用独立数据库，避免 LSM migration 对 raw 场景造成顺序依赖。
        arguments.add("--spring.datasource.url=" + jdbcUrl(databaseName));
        arguments.add("--spring.datasource.username=" + POSTGRES.getUsername());
        arguments.add("--spring.datasource.password=" + POSTGRES.getPassword());
        arguments.add("--spring.main.banner-mode=off");
        arguments.add("--server.port=0");
        arguments.add("--data-agent.runtime.scheduling-enabled=false");
        if (strategy != null) {
            arguments.add("--" + STRATEGY_PROPERTY + "=" + strategy);
        }
        return application.run(arguments.toArray(String[]::new));
    }

    private static String createDatabase() throws SQLException {
        POSTGRES.start();
        String databaseName = DATABASE_PREFIX + DATABASE_SEQUENCE.incrementAndGet();
        // 数据库名由固定前缀和本地递增序号组成，不拼接任何外部输入。
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
            throws SQLException {
        javax.sql.DataSource dataSource = context.getBean(javax.sql.DataSource.class);
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData().getTables(null, "public", tableName, null)) {
            return tables.next();
        }
    }
}
