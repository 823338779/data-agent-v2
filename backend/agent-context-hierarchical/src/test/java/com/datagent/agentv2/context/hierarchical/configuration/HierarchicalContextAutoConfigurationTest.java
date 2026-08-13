package com.datagent.agentv2.context.hierarchical.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCompactionJobMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalExecutionPolicyMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalSummaryNodeMapper;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.FilterType;

class HierarchicalContextAutoConfigurationTest {

    /** Selects the raw strategy, which must leave this plugin inactive. */
    private static final String STRATEGY_RAW = "data-agent.context.strategy=raw";

    /** Selects the hierarchical strategy, which activates property binding. */
    private static final String STRATEGY_HIERARCHICAL = "data-agent.context.strategy=hierarchical";

    /** Selects the unrelated LSM plugin, which must leave this plugin inactive. */
    private static final String STRATEGY_LSM = "data-agent.context.strategy=lsm";

    /** Names the private Flyway bean that must initialize before the strategy factory. */
    private static final String HIERARCHICAL_FLYWAY_BEAN_NAME = "hierarchicalContextFlyway";

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
                            AutoConfigurations.of(HierarchicalContextAutoConfiguration.class))
                    .withUserConfiguration(HierarchicalPluginComponentScan.class);

    @Test
    void rawDoesNotBindHierarchicalPropertiesOrCreatePluginBeans() {
        assertPluginInactive(STRATEGY_RAW);
    }

    @Test
    void lsmDoesNotBindHierarchicalPropertiesOrCreatePluginBeans() {
        assertPluginInactive(STRATEGY_LSM);
    }

    @Test
    void hierarchicalStrategyFactoryDependsOnPluginFlywayAndExposesSharedSpi() throws Exception {
        Method factory = strategyFactory();
        DependsOn dependsOn = factory.getAnnotation(DependsOn.class);
        HierarchicalContextAutoConfiguration configuration =
                new HierarchicalContextAutoConfiguration();

        // The factory contract makes migration completion a creation-time prerequisite.
        assertThat(dependsOn).isNotNull();
        assertThat(dependsOn.value()).containsExactly(HIERARCHICAL_FLYWAY_BEAN_NAME);

        ConversationContextStrategy strategy =
                configuration.hierarchicalConversationContextStrategy(
                        mock(HierarchicalExecutionPolicyRepository.class),
                        mock(HierarchicalConversationContextAssembler.class),
                        mock(HierarchicalCompactionCoordinator.class),
                        mock(HierarchicalContextMetrics.class),
                        new HierarchicalContextProperties());
        assertThat(strategy).isInstanceOf(HierarchicalConversationContextStrategy.class);
        assertThat(strategy.strategyId()).isEqualTo("hierarchical");
    }

    @Test
    void hierarchicalRejectsTargetAtOrAboveTrigger() {
        new ApplicationContextRunner()
                .withUserConfiguration(HierarchicalPropertiesBinding.class)
                .withPropertyValues(STRATEGY_HIERARCHICAL, TRIGGER_RATIO, TARGET_RATIO_AT_TRIGGER)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(BindValidationException.class);
                        });
    }

    private void assertPluginInactive(String selectedStrategy) {
        contextRunner
                .withPropertyValues(selectedStrategy)
                .run(
                        context -> {
                            assertThat(context)
                                    .doesNotHaveBean(HierarchicalContextProperties.class)
                                    .doesNotHaveBean(HierarchicalConversationContextStrategy.class);
                            assertThat(
                                            context.getBeanFactory()
                                                    .containsBeanDefinition(
                                                            HIERARCHICAL_FLYWAY_BEAN_NAME))
                                    .isFalse();
                            assertThat(context)
                                    .doesNotHaveBean(HierarchicalCheckpointMapper.class)
                                    .doesNotHaveBean(HierarchicalCompactionJobMapper.class)
                                    .doesNotHaveBean(HierarchicalExecutionPolicyMapper.class)
                                    .doesNotHaveBean(HierarchicalSummaryNodeMapper.class);
                        });
    }

    private static Method strategyFactory() throws NoSuchMethodException {
        return HierarchicalContextAutoConfiguration.class.getDeclaredMethod(
                "hierarchicalConversationContextStrategy",
                HierarchicalExecutionPolicyRepository.class,
                HierarchicalConversationContextAssembler.class,
                HierarchicalCompactionCoordinator.class,
                HierarchicalContextMetrics.class,
                HierarchicalContextProperties.class);
    }

    /**
     * Mirrors the server's root package scan so raw/lsm tests catch accidental plugin components.
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = HierarchicalContextAutoConfiguration.class,
            excludeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = HierarchicalPropertiesBinding.class))
    static class HierarchicalPluginComponentScan {}

    /** Isolates property validation from Flyway and mapper infrastructure. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HierarchicalContextProperties.class)
    static class HierarchicalPropertiesBinding {}
}
