package com.datagent.agentv2.context.hierarchical.configuration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** 使用独立位置和历史表初始化 hierarchical 插件数据库对象。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
public class HierarchicalFlywayMigrator {

    static final String MIGRATION_LOCATION = "classpath:db/agent-context-hierarchical/migration";
    static final String HISTORY_TABLE = "flyway_agent_context_hierarchical_schema_history";
    static final String PRIMARY_FLYWAY_INITIALIZER = "flywayInitializer";
    static final String INITIAL_BASELINE_VERSION = "0";

    /** 在主持久化迁移完成后运行插件私有 Flyway。 */
    @Bean(name = "hierarchicalContextFlyway", initMethod = "migrate")
    @DependsOn(PRIMARY_FLYWAY_INITIALIZER)
    HierarchicalFlywayMigration hierarchicalContextFlyway(DataSource dataSource) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations(MIGRATION_LOCATION)
                        .table(HISTORY_TABLE)
                        .baselineOnMigrate(true)
                        .baselineVersion(INITIAL_BASELINE_VERSION)
                        .load();
        return new HierarchicalFlywayMigration(flyway);
    }

    /** 包装底层 Flyway，避免其 Bean 类型使主 Flyway 自动配置提前退避。 */
    static final class HierarchicalFlywayMigration {

        /** 仅包含插件迁移位置和独立历史表的 Flyway 实例。 */
        private final Flyway flyway;

        private HierarchicalFlywayMigration(Flyway flyway) {
            this.flyway = flyway;
        }

        /** 执行插件私有迁移。 */
        void migrate() {
            flyway.migrate();
        }
    }
}
