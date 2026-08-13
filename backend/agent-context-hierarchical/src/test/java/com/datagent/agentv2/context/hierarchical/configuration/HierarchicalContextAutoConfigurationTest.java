package com.datagent.agentv2.context.hierarchical.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HierarchicalContextAutoConfigurationTest {

    /** Selects the raw strategy, which must leave this plugin inactive. */
    private static final String STRATEGY_RAW = "data-agent.context.strategy=raw";

    /** Selects the hierarchical strategy, which activates property binding. */
    private static final String STRATEGY_HIERARCHICAL = "data-agent.context.strategy=hierarchical";

    /** Provides a valid trigger watermark for the invalid-ordering test. */
    private static final String TRIGGER_RATIO =
            "data-agent.context.hierarchical.trigger-ratio=0.75";

    /** Sets the target equal to the trigger, which must be rejected. */
    private static final String TARGET_RATIO_AT_TRIGGER =
            "data-agent.context.hierarchical.target-ratio=0.75";

    /** Exercises the plugin boundary with the real conditional auto-configuration. */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(HierarchicalContextAutoConfiguration.class));

    @Test
    void rawDoesNotBindHierarchicalPropertiesOrCreatePluginBeans() {
        contextRunner
                .withPropertyValues(STRATEGY_RAW)
                .run(
                        context ->
                                assertThat(context)
                                        .doesNotHaveBean(HierarchicalContextProperties.class));
    }

    @Test
    void hierarchicalRejectsTargetAtOrAboveTrigger() {
        contextRunner
                .withPropertyValues(STRATEGY_HIERARCHICAL, TRIGGER_RATIO, TARGET_RATIO_AT_TRIGGER)
                .run(context -> assertThat(context).hasFailed());
    }
}
