package com.datagent.agentv2.context.lsm.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LsmContextAutoConfigurationTest {

    private static final String STRATEGY_RAW = "data-agent.context.strategy=raw";
    private static final String STRATEGY_LSM = "data-agent.context.strategy=lsm";
    private static final String INVALID_FANOUT = "data-agent.context.compaction.fanout=2";
    private static final String INVALID_MAX_LEVEL = "data-agent.context.compaction.max-level=9";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(LsmContextAutoConfiguration.class));

    @Test
    void rawDoesNotCreateLsmBeansOrRunPluginMigration() {
        contextRunner
                .withPropertyValues(STRATEGY_RAW)
                .run(context -> assertThat(context).doesNotHaveBean(LsmContextProperties.class));
    }

    @Test
    void lsmRejectsFanoutBelowThree() {
        assertInvalidConfiguration(INVALID_FANOUT);
    }

    @Test
    void lsmRejectsMaxLevelAboveEight() {
        assertInvalidConfiguration(INVALID_MAX_LEVEL);
    }

    private void assertInvalidConfiguration(String invalidProperty) {
        contextRunner
                .withBean("flywayInitializer", Object.class, Object::new)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues(STRATEGY_LSM, invalidProperty)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(BindValidationException.class);
                        });
    }
}
