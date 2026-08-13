package com.datagent.agentv2.context.hierarchical.configuration;

import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCompactionJobMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalExecutionPolicyMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalSummaryNodeMapper;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.hierarchical.planning.ConversationTurnGrouper;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalConversationHistoryLoader;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalInvariantValidator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCheckpointPublicationTransaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionClaimTransaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionRecoveryService;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalGenerationSourceProvider;
import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.context.hierarchical.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalNodeKeyFactory;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
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
import tools.jackson.databind.ObjectMapper;

/** 仅当服务显式选择 hierarchical 策略时装载插件配置。 */
@AutoConfiguration
@AutoConfigureAfter(FlywayAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
@EnableConfigurationProperties(HierarchicalContextProperties.class)
@Import({HierarchicalMapperConfiguration.class, HierarchicalFlywayMigrator.class})
public class HierarchicalContextAutoConfiguration {

    private static final String HIERARCHICAL_FLYWAY_BEAN_NAME = "hierarchicalContextFlyway";

    /** 单个层级或 Cold 摘要文档允许的默认最大估算 token 数。 */
    private static final int DEFAULT_MAX_SUMMARY_TOKENS = 1_024;

    @Bean
    HierarchicalContextMetrics hierarchicalContextMetrics(MeterRegistry meterRegistry) {
        return new HierarchicalContextMetrics(meterRegistry);
    }

    @Bean
    HierarchicalDocumentCodec hierarchicalDocumentCodec() {
        return new HierarchicalDocumentCodec();
    }

    // 仓储 Bean 显式绑定插件私有 Mapper，避免依赖宿主应用的组件扫描范围。
    @Bean
    HierarchicalExecutionPolicyRepository hierarchicalExecutionPolicyRepository(
            HierarchicalExecutionPolicyMapper mapper,
            HierarchicalDocumentCodec documents,
            IdGenerator ids) {
        return new HierarchicalExecutionPolicyRepository(mapper, documents, ids);
    }

    @Bean
    HierarchicalCheckpointRepository hierarchicalCheckpointRepository(
            HierarchicalCheckpointMapper mapper, HierarchicalDocumentCodec documents) {
        return new HierarchicalCheckpointRepository(mapper, documents);
    }

    @Bean
    HierarchicalSummaryNodeRepository hierarchicalSummaryNodeRepository(
            HierarchicalSummaryNodeMapper mapper, HierarchicalDocumentCodec documents) {
        return new HierarchicalSummaryNodeRepository(mapper, documents);
    }

    @Bean
    HierarchicalCompactionJobRepository hierarchicalCompactionJobRepository(
            HierarchicalCompactionJobMapper mapper, HierarchicalDocumentCodec documents) {
        return new HierarchicalCompactionJobRepository(mapper, documents);
    }

    // 历史、估算与摘要组件共享同一冻结读取和 schema 校验实现。
    @Bean
    ConversationTurnGrouper conversationTurnGrouper() {
        return new ConversationTurnGrouper();
    }

    @Bean
    HierarchicalConversationHistoryLoader hierarchicalConversationHistoryLoader(
            AgentExecutionService executions,
            ConversationMessageService messages,
            ToolExecutionService toolExecutions,
            ObjectMapper json,
            ConversationTurnGrouper grouper) {
        return new HierarchicalConversationHistoryLoader(
                executions, messages, toolExecutions, json, grouper);
    }

    @Bean
    ConversationTokenEstimator conversationTokenEstimator() {
        return new ConversationTokenEstimator();
    }

    @Bean
    ContextSummaryDocumentValidator contextSummaryDocumentValidator(
            ConversationTokenEstimator estimator) {
        return new ContextSummaryDocumentValidator(estimator);
    }

    @Bean
    HierarchicalNodeKeyFactory hierarchicalNodeKeyFactory() {
        return new HierarchicalNodeKeyFactory();
    }

    @Bean
    HierarchicalSummaryGenerator hierarchicalSummaryGenerator(
            ContextSummaryModelPort model,
            ContextSummaryDocumentValidator validator,
            HierarchicalNodeKeyFactory keys) {
        return new HierarchicalSummaryGenerator(model, validator, keys);
    }

    @Bean
    BinarySummaryTreePlanner binarySummaryTreePlanner(HierarchicalContextProperties properties) {
        return new BinarySummaryTreePlanner(properties.snapshot());
    }

    @Bean
    HierarchicalInvariantValidator hierarchicalInvariantValidator() {
        return new HierarchicalInvariantValidator();
    }

    @Bean
    HierarchicalConversationContextAssembler hierarchicalConversationContextAssembler(
            HierarchicalConversationHistoryLoader history,
            ConversationTokenEstimator estimator,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalSummaryNodeRepository nodes,
            ObjectMapper json) {
        return new HierarchicalConversationContextAssembler(
                history, estimator, checkpoints, nodes, json);
    }

    /** 生成阶段复用 assembler 的 checkpoint fence 和 history 冻结边界，不复制读取逻辑。 */
    @Bean
    HierarchicalGenerationSourceProvider hierarchicalGenerationSourceProvider(
            HierarchicalConversationContextAssembler assembler) {
        return assembler::loadGenerationSources;
    }

    // durable 规划、生成、接管、发布和恢复各自保持原有事务边界。
    @Bean
    HierarchicalCompactionPlanningTransaction hierarchicalCompactionPlanningTransaction(
            BinarySummaryTreePlanner planner,
            HierarchicalSummaryGenerator generator,
            HierarchicalCompactionJobRepository jobs,
            IdGenerator ids,
            ContextExecutionControlPort executions,
            ObjectMapper json) {
        return new HierarchicalCompactionPlanningTransaction(
                planner, generator, jobs, ids, executions, json);
    }

    @Bean
    HierarchicalCandidateGenerationService hierarchicalCandidateGenerationService(
            HierarchicalSummaryGenerator generator,
            HierarchicalSummaryNodeRepository nodes,
            HierarchicalCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            HierarchicalGenerationSourceProvider sources,
            HierarchicalContextMetrics metrics) {
        return new HierarchicalCandidateGenerationService(
                generator, nodes, jobs, executions, sources, metrics);
    }

    @Bean
    HierarchicalCompactionClaimTransaction hierarchicalCompactionClaimTransaction(
            HierarchicalCompactionJobRepository jobs, ContextExecutionControlPort executions) {
        return new HierarchicalCompactionClaimTransaction(jobs, executions);
    }

    @Bean
    HierarchicalCompactionRecoveryService hierarchicalCompactionRecoveryService(
            HierarchicalCompactionJobRepository jobs,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalCompactionPlanningTransaction planning,
            HierarchicalCandidateGenerationService generation,
            HierarchicalCompactionClaimTransaction claims) {
        return new HierarchicalCompactionRecoveryService(
                jobs, checkpoints, planning, generation, claims);
    }

    @Bean
    HierarchicalCheckpointPublicationTransaction hierarchicalCheckpointPublicationTransaction(
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalCompactionJobRepository jobs,
            HierarchicalSummaryNodeRepository nodes,
            HierarchicalCompactionPlanningTransaction planning,
            HierarchicalCandidateGenerationService generation,
            ContextExecutionControlPort executions,
            HierarchicalInvariantValidator invariants,
            ObjectMapper json) {
        return new HierarchicalCheckpointPublicationTransaction(
                checkpoints, jobs, nodes, planning, generation, executions, invariants, json);
    }

    @Bean
    HierarchicalCompactionCoordinator hierarchicalCompactionCoordinator(
            HierarchicalCompactionPlanningTransaction planning,
            HierarchicalCandidateGenerationService generation,
            HierarchicalCompactionRecoveryService recovery,
            HierarchicalCheckpointPublicationTransaction publication,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCheckpointRepository checkpoints,
            ContextExecutionControlPort executions) {
        return new HierarchicalCompactionCoordinator(
                planning,
                generation,
                recovery,
                publication,
                assembler,
                checkpoints,
                executions,
                DEFAULT_MAX_SUMMARY_TOKENS);
    }

    /** 只有插件私有迁移成功初始化后才允许共享策略解析器看到 hierarchical。 */
    @Bean
    @DependsOn(HIERARCHICAL_FLYWAY_BEAN_NAME)
    HierarchicalConversationContextStrategy hierarchicalConversationContextStrategy(
            HierarchicalExecutionPolicyRepository policies,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCompactionCoordinator coordinator,
            HierarchicalContextMetrics metrics,
            HierarchicalContextProperties properties) {
        return new HierarchicalConversationContextStrategy(
                policies, assembler, coordinator, metrics, properties);
    }
}
