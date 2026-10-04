package com.datagent.agentv2.context.hierarchical.configuration;

import com.datagent.agentv2.context.common.summary.EventContextSummaryRenderer;
import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTurnGrouper;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalStepBudgetPlanner;
import com.datagent.agentv2.context.hierarchical.planning.RollingColdCompactionPlanner;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalStepContextEngine;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalStepPublisher;
import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalColdKeyFactory;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.platform.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

/** 仅装配单一滚动 Cold，不再注册 Warm 或二叉摘要树组件。 */
@AutoConfiguration
@AutoConfigureAfter(FlywayAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
@EnableConfigurationProperties({
    HierarchicalContextProperties.class,
    StepCompactionProperties.class
})
@Import({HierarchicalMapperConfiguration.class, HierarchicalFlywayMigrator.class})
public class HierarchicalContextAutoConfiguration {
    private static final String FLYWAY = "hierarchicalContextFlyway";
    private static final int MAX_SUMMARY_OUTPUT_TOKENS = 1024;
    private static final int SUMMARY_INPUT_HARD_TOKENS = 32768;

    @Bean
    HierarchicalContextMetrics hierarchicalContextMetrics(MeterRegistry registry) {
        return new HierarchicalContextMetrics(registry);
    }

    @Bean
    HierarchicalDocumentCodec hierarchicalDocumentCodec() {
        return new HierarchicalDocumentCodec();
    }

    @Bean
    ConversationTurnGrouper conversationTurnGrouper() {
        return new ConversationTurnGrouper();
    }

    @Bean
    HierarchicalConversationHistoryLoader hierarchicalConversationHistoryLoader(
            AgentExecutionService executions,
            ConversationMessageService messages,
            ObjectMapper json,
            ConversationTurnGrouper grouper) {
        return new HierarchicalConversationHistoryLoader(executions, messages, json, grouper);
    }

    @Bean
    ConversationTokenEstimator conversationTokenEstimator() {
        return new ConversationTokenEstimator();
    }

    @Bean
    EventContextSummaryValidator eventContextSummaryValidator() {
        return new EventContextSummaryValidator();
    }

    @Bean
    EventContextSummaryRenderer eventContextSummaryRenderer() {
        return new EventContextSummaryRenderer();
    }

    @Bean
    HierarchicalColdKeyFactory hierarchicalColdKeyFactory() {
        return new HierarchicalColdKeyFactory();
    }

    @Bean
    RollingColdCompactionPlanner rollingColdCompactionPlanner(
            ConversationTokenEstimator estimator) {
        return new RollingColdCompactionPlanner(estimator);
    }

    @Bean
    RollingColdSummaryGenerator rollingColdSummaryGenerator(
            ContextSummaryModelPort model,
            EventContextSummaryValidator validator,
            EventContextSummaryRenderer renderer,
            ConversationTokenEstimator estimator,
            HierarchicalColdKeyFactory keys) {
        return new RollingColdSummaryGenerator(model, validator, renderer, estimator, keys);
    }

    @Bean
    HierarchicalConversationContextAssembler hierarchicalConversationContextAssembler(
            HierarchicalConversationHistoryLoader history,
            ConversationTokenEstimator estimator,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalColdRepository colds,
            EventContextSummaryValidator validator,
            EventContextSummaryRenderer renderer) {
        return new HierarchicalConversationContextAssembler(
                history, estimator, checkpoints, colds, validator, renderer);
    }

    @Bean
    HierarchicalCompactionCoordinator hierarchicalCompactionCoordinator(
            RollingColdCompactionPlanner planner,
            RollingColdSummaryGenerator generator,
            HierarchicalColdRepository colds,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalConversationContextAssembler assembler,
            ContextExecutionControlPort executions) {
        return new HierarchicalCompactionCoordinator(
                planner,
                generator,
                colds,
                checkpoints,
                assembler,
                executions,
                MAX_SUMMARY_OUTPUT_TOKENS,
                SUMMARY_INPUT_HARD_TOKENS);
    }

    @Bean
    @DependsOn(FLYWAY)
    HierarchicalConversationContextStrategy hierarchicalConversationContextStrategy(
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCompactionCoordinator coordinator,
            HierarchicalContextMetrics metrics,
            ContextSummaryModelPort model,
            IdGenerator ids,
            HierarchicalContextProperties properties,
            HierarchicalStepContextEngine stepEngine) {
        return new HierarchicalConversationContextStrategy(
                checkpoints, assembler, coordinator, metrics, model, ids, properties, stepEngine);
    }

    @Bean
    HierarchicalStepPublisher hierarchicalStepPublisher(
            HierarchicalConversationHistoryLoader history,
            HierarchicalColdRepository colds,
            HierarchicalCheckpointRepository checkpoints,
            ContextExecutionControlPort control) {
        return new HierarchicalStepPublisher(history, colds, checkpoints, control);
    }

    @Bean
    HierarchicalStepContextEngine hierarchicalStepContextEngine(
            HierarchicalConversationHistoryLoader history,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalColdRepository colds,
            StepCompactionProperties properties,
            ConversationTokenEstimator estimator,
            ContextSummaryModelPort model,
            ContextExecutionControlPort control,
            HierarchicalStepPublisher publisher,
            HierarchicalContextMetrics metrics) {
        var protocol =
                new com.datagent.agentv2.context.hierarchical.summary
                        .HierarchicalStepSummaryProtocol();
        return new HierarchicalStepContextEngine(
                history,
                checkpoints,
                colds,
                properties,
                estimator,
                protocol,
                new HierarchicalStepBudgetPlanner(estimator, properties),
                new com.datagent.agentv2.context.hierarchical.summary
                        .HierarchicalStepSummaryGenerator(model, protocol, estimator),
                publisher,
                control,
                model,
                metrics);
    }
}
