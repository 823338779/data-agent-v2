package com.datagent.agentv2.context.lsm.configuration;

import com.datagent.agentv2.context.common.summary.EventContextSummaryRenderer;
import com.datagent.agentv2.context.common.summary.EventContextSummaryValidator;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.mapping.LsmPersistenceStructMapper;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCandidateGenerator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.context.lsm.runtime.LsmManifestPublicationTransaction;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
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

/** 仅当服务明确选择 lsm 策略时装载插件基础设施。 */
@AutoConfiguration
@AutoConfigureAfter(FlywayAutoConfiguration.class)
@ConditionalOnProperty(prefix = "data-agent.context", name = "strategy", havingValue = "lsm")
@EnableConfigurationProperties(LsmContextProperties.class)
@Import({LsmMapperConfiguration.class, LsmFlywayMigrator.class})
public class LsmContextAutoConfiguration {

    private static final String LSM_FLYWAY_BEAN_NAME = "lsmContextFlyway";

    @Bean
    LsmContextMetrics lsmContextMetrics(MeterRegistry meterRegistry) {
        return new LsmContextMetrics(meterRegistry);
    }

    @Bean
    ConversationTokenEstimator conversationTokenEstimator() {
        return new ConversationTokenEstimator();
    }

    @Bean
    LsmInvariantValidator lsmInvariantValidator() {
        return new LsmInvariantValidator();
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
    ContextSummaryGenerator contextSummaryGenerator(
            ContextSummaryModelPort model,
            EventContextSummaryValidator validator,
            EventContextSummaryRenderer renderer,
            ConversationTokenEstimator estimator,
            LsmContextMetrics metrics) {
        return new ContextSummaryGenerator(
                model, validator, renderer, estimator, new LsmSegmentKeyFactory(), metrics, 32_768);
    }

    @Bean
    LsmCandidateGenerator lsmCandidateGenerator(
            ContextSummaryGenerator generator,
            LsmSummarySegmentRepository segments,
            LsmDocumentCodec documents,
            IdGenerator ids,
            LsmPersistenceStructMapper models) {
        return new LsmCandidateGenerator(generator, segments, documents, ids, models);
    }

    @Bean
    LsmManifestPublicationTransaction lsmManifestPublicationTransaction(
            LsmContextManifestRepository manifests,
            LsmSummarySegmentRepository segments,
            ContextExecutionControlPort executions,
            LsmInvariantValidator invariants,
            LsmDocumentCodec documents,
            LsmContextMetrics metrics) {
        return new LsmManifestPublicationTransaction(
                manifests, segments, executions, invariants, documents, metrics);
    }

    @Bean
    LsmCompactionCoordinator lsmCompactionCoordinator(
            LsmCandidateGenerator generator,
            LsmManifestPublicationTransaction publication,
            ContextExecutionControlPort executions,
            LsmContextMetrics metrics) {
        return new LsmCompactionCoordinator(
                new LsmCompactionPlanner(), generator, publication, executions, metrics);
    }

    @Bean
    LsmConversationContextAssembler lsmConversationContextAssembler(
            AgentExecutionService executions,
            ConversationMessageService messages,
            LsmContextManifestRepository manifests,
            LsmSummarySegmentRepository segments,
            LsmDocumentCodec documents,
            ConversationTokenEstimator estimator,
            ContextExecutionControlPort executionControl,
            IdGenerator ids,
            tools.jackson.databind.ObjectMapper json,
            LsmPersistenceStructMapper models,
            LsmContextMetrics metrics,
            EventContextSummaryValidator summaryValidator,
            EventContextSummaryRenderer summaryRenderer) {
        return new LsmConversationContextAssembler(
                executions,
                messages,
                manifests,
                segments,
                documents,
                estimator,
                executionControl,
                ids,
                json,
                models,
                metrics,
                summaryValidator,
                summaryRenderer);
    }

    @Bean
    @DependsOn(LSM_FLYWAY_BEAN_NAME)
    LsmConversationContextStrategy lsmConversationContextStrategy(
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator,
            LsmContextMetrics metrics,
            LsmContextProperties properties) {
        return new LsmConversationContextStrategy(assembler, coordinator, metrics, properties);
    }
}
