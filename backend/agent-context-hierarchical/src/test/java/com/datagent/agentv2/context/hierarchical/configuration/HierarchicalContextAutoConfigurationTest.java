package com.datagent.agentv2.context.hierarchical.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.datagent.agentv2.context.hierarchical.HierarchicalPluginPackageMarker;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCompactionJobMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalExecutionPolicyMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalSummaryNodeMapper;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalContextAutoConfigurationTest {

    /** Selects the raw strategy, which must leave this plugin inactive. */
    private static final String STRATEGY_RAW = "data-agent.context.strategy=raw";

    /** Selects the hierarchical strategy, which activates property binding. */
    private static final String STRATEGY_HIERARCHICAL = "data-agent.context.strategy=hierarchical";

    /** Selects the unrelated LSM plugin, which must leave this plugin inactive. */
    private static final String STRATEGY_LSM = "data-agent.context.strategy=lsm";

    /** Names the private Flyway bean that must initialize before the strategy factory. */
    private static final String HIERARCHICAL_FLYWAY_BEAN_NAME = "hierarchicalContextFlyway";

    /** Names the strategy factory so its runtime dependency metadata can be asserted. */
    private static final String HIERARCHICAL_STRATEGY_BEAN_NAME =
            "hierarchicalConversationContextStrategy";

    /** Plugin migration history proves the private migration completed before strategy access. */
    private static final String HIERARCHICAL_HISTORY_COUNT_SQL =
            "select count(*) from flyway_agent_context_hierarchical_schema_history";

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
    void hierarchicalStartsTheCompleteBeanGraphAfterPluginMigration() {
        try (PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            hierarchicalContextRunner(postgres)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context)
                                        .hasSingleBean(HierarchicalExecutionPolicyRepository.class)
                                        .hasSingleBean(HierarchicalCheckpointRepository.class)
                                        .hasSingleBean(HierarchicalSummaryNodeRepository.class)
                                        .hasSingleBean(HierarchicalCompactionJobRepository.class)
                                        .hasSingleBean(BinarySummaryTreePlanner.class)
                                        .hasSingleBean(
                                                HierarchicalConversationContextAssembler.class)
                                        .hasSingleBean(HierarchicalCompactionCoordinator.class)
                                        .hasSingleBean(HierarchicalContextMetrics.class)
                                        .hasSingleBean(
                                                HierarchicalConversationContextStrategy.class);

                                // A real migration and BeanDefinition dependency prove runtime
                                // order.
                                JdbcTemplate jdbc =
                                        new JdbcTemplate(context.getBean(DataSource.class));
                                assertThat(
                                                jdbc.queryForObject(
                                                        HIERARCHICAL_HISTORY_COUNT_SQL, Long.class))
                                        .isPositive();
                                assertThat(
                                                context.getBeanFactory()
                                                        .getBeanDefinition(
                                                                HIERARCHICAL_STRATEGY_BEAN_NAME)
                                                        .getDependsOn())
                                        .containsExactly(HIERARCHICAL_FLYWAY_BEAN_NAME);
                                ConversationContextStrategy strategy =
                                        context.getBean(
                                                HierarchicalConversationContextStrategy.class);
                                assertThat(strategy.strategyId()).isEqualTo("hierarchical");
                            });
        }
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
                                    .doesNotHaveBean(HierarchicalFlywayMigrator.class)
                                    .doesNotHaveBean(HierarchicalConversationContextStrategy.class)
                                    .doesNotHaveBean(HierarchicalExecutionPolicyRepository.class)
                                    .doesNotHaveBean(HierarchicalCheckpointRepository.class)
                                    .doesNotHaveBean(HierarchicalSummaryNodeRepository.class)
                                    .doesNotHaveBean(HierarchicalCompactionJobRepository.class)
                                    .doesNotHaveBean(BinarySummaryTreePlanner.class)
                                    .doesNotHaveBean(HierarchicalConversationContextAssembler.class)
                                    .doesNotHaveBean(HierarchicalCompactionCoordinator.class)
                                    .doesNotHaveBean(HierarchicalContextMetrics.class);
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

    private static ApplicationContextRunner hierarchicalContextRunner(
            PostgreSQLContainer postgres) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                DataSourceAutoConfiguration.class,
                                FlywayAutoConfiguration.class,
                                MybatisPlusAutoConfiguration.class,
                                HierarchicalContextAutoConfiguration.class))
                .withUserConfiguration(FullGraphExternalDependencies.class)
                .withPropertyValues(
                        STRATEGY_HIERARCHICAL,
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword());
    }

    /**
     * Mirrors the server's root package scan so raw/lsm tests catch accidental plugin components.
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = HierarchicalPluginPackageMarker.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*"))
    static class HierarchicalPluginComponentScan {}

    /** Isolates property validation from Flyway and mapper infrastructure. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HierarchicalContextProperties.class)
    static class HierarchicalPropertiesBinding {}

    /**
     * Supplies only host-owned dependencies while the plugin creates its complete internal graph.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(MybatisPlusConfiguration.class)
    static class FullGraphExternalDependencies {

        @Bean
        AgentExecutionService agentExecutionService() {
            return mock(AgentExecutionService.class);
        }

        @Bean
        ConversationMessageService conversationMessageService() {
            return mock(ConversationMessageService.class);
        }

        @Bean
        ToolExecutionService toolExecutionService() {
            return mock(ToolExecutionService.class);
        }

        @Bean
        ContextExecutionControlPort contextExecutionControlPort() {
            return mock(ContextExecutionControlPort.class);
        }

        @Bean
        ContextSummaryModelPort contextSummaryModelPort() {
            return mock(ContextSummaryModelPort.class);
        }

        @Bean
        IdGenerator idGenerator() {
            return mock(IdGenerator.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
