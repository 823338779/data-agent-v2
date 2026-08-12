package com.datagent.agentv2.context.lsm.configuration;

import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionPlanningTransaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionRecoveryService;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.context.lsm.runtime.LsmManifestPublicationTransaction;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
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
    ContextSummaryDocumentValidator contextSummaryDocumentValidator() {
        return new ContextSummaryDocumentValidator();
    }

    @Bean
    ContextSummaryGenerator contextSummaryGenerator(
            ContextSummaryModelPort model,
            ContextSummaryDocumentValidator validator,
            ConversationTokenEstimator estimator,
            LsmContextMetrics metrics) {
        return new ContextSummaryGenerator(
                model, validator, estimator, new LsmSegmentKeyFactory(), metrics);
    }

    @Bean
    LsmCompactionPlanningTransaction lsmCompactionPlanningTransaction(
            ContextSummaryGenerator generator,
            LsmCompactionJobRepository jobs,
            LsmContextManifestRepository manifests,
            LsmDocumentCodec documents,
            IdGenerator ids,
            ContextExecutionControlPort executions) {
        return new LsmCompactionPlanningTransaction(
                new LsmCompactionPlanner(), generator, jobs, manifests, documents, ids, executions);
    }

    @Bean
    LsmCompactionRecoveryService lsmCompactionRecoveryService(
            ContextSummaryGenerator generator,
            LsmSummarySegmentRepository segments,
            LsmCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            LsmDocumentCodec documents,
            IdGenerator ids) {
        return new LsmCompactionRecoveryService(
                generator, segments, jobs, executions, documents, ids);
    }

    @Bean
    LsmManifestPublicationTransaction lsmManifestPublicationTransaction(
            LsmContextManifestRepository manifests,
            LsmSummarySegmentRepository segments,
            LsmCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            LsmInvariantValidator invariants,
            LsmDocumentCodec documents,
            LsmContextMetrics metrics) {
        return new LsmManifestPublicationTransaction(
                manifests, segments, jobs, executions, invariants, documents, metrics);
    }

    @Bean
    LsmCompactionCoordinator lsmCompactionCoordinator(
            LsmCompactionPlanningTransaction planning,
            LsmCompactionRecoveryService recovery,
            LsmManifestPublicationTransaction publication,
            LsmContextMetrics metrics) {
        return new LsmCompactionCoordinator(planning, recovery, publication, metrics);
    }

    @Bean
    LsmConversationContextAssembler lsmConversationContextAssembler(
            AgentExecutionService executions,
            ConversationMessageService messages,
            ToolExecutionService toolExecutions,
            LsmContextManifestRepository manifests,
            LsmSummarySegmentRepository segments,
            LsmDocumentCodec documents,
            ConversationTokenEstimator estimator,
            ContextExecutionControlPort executionControl,
            IdGenerator ids,
            tools.jackson.databind.ObjectMapper json,
            LsmContextMetrics metrics) {
        return new LsmConversationContextAssembler(
                executions,
                messages,
                toolExecutions,
                manifests,
                segments,
                documents,
                estimator,
                executionControl,
                ids,
                json,
                metrics);
    }

    @Bean
    @DependsOn(LSM_FLYWAY_BEAN_NAME)
    LsmConversationContextStrategy lsmConversationContextStrategy(
            LsmExecutionPolicyRepository policies,
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator,
            LsmContextMetrics metrics) {
        return new LsmConversationContextStrategy(policies, assembler, coordinator, metrics);
    }
}
