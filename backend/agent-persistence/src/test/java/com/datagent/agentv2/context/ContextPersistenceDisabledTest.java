package com.datagent.agentv2.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalFlywayMigrator;
import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalMapperConfiguration;
import com.datagent.agentv2.context.lsm.configuration.LsmFlywayMigrator;
import com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** raw 策略即使没有 DataSource，也不能尝试装配摘要 Mapper 或迁移。 */
class ContextPersistenceDisabledTest {
    @Test
    void rawStrategyDoesNotLoadContextStorageOrMigrations() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        LsmFlywayMigrator.class,
                        LsmMapperConfiguration.class,
                        HierarchicalFlywayMigrator.class,
                        HierarchicalMapperConfiguration.class)
                .withPropertyValues("data-agent.context.strategy=raw")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean("lsmContextFlyway");
                            assertThat(context).doesNotHaveBean("hierarchicalContextFlyway");
                            assertThat(context).doesNotHaveBean("lsmContextManifestRepository");
                            assertThat(context).doesNotHaveBean("hierarchicalCheckpointRepository");
                        });
    }
}
