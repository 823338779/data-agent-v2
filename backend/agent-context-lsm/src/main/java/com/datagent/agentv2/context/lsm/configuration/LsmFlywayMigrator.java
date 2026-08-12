package com.datagent.agentv2.context.lsm.configuration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** 使用独立迁移位置和历史表管理插件数据库对象。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.context", name = "strategy", havingValue = "lsm")
public class LsmFlywayMigrator {

    static final String MIGRATION_LOCATION = "classpath:db/agent-context-lsm/migration";
    static final String HISTORY_TABLE = "flyway_agent_context_lsm_schema_history";
    static final String PRIMARY_FLYWAY_INITIALIZER = "flywayInitializer";
    static final String INITIAL_BASELINE_VERSION = "0";

    @Bean(name = "lsmContextFlyway", initMethod = "migrate")
    @DependsOn(PRIMARY_FLYWAY_INITIALIZER)
    Flyway lsmContextFlyway(DataSource dataSource, LsmContextProperties properties) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion(INITIAL_BASELINE_VERSION)
                .load();
    }
}
