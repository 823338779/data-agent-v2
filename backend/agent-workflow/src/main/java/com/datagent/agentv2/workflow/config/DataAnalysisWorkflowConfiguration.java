package com.datagent.agentv2.workflow.config;

import com.datagent.agentv2.datasource.config.StarRocksDataSourceConfiguration;
import com.datagent.agentv2.datasource.config.StarRocksDataSourceProperties;
import com.datagent.agentv2.datasource.query.StarRocksQueryGateway;
import com.datagent.agentv2.datasource.query.StarRocksSqlPolicyValidator;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSearchService;
import com.datagent.agentv2.workflow.observability.DataAnalysisWorkflowMetrics;
import com.datagent.agentv2.workflow.port.WorkflowEventPort;
import com.datagent.agentv2.workflow.port.WorkflowExecutionControlPort;
import com.datagent.agentv2.workflow.port.WorkflowFinalAnswerPort;
import com.datagent.agentv2.workflow.port.WorkflowModelPort;
import com.datagent.agentv2.workflow.runtime.DataAnalysisWorkflowEngine;
import com.datagent.agentv2.workflow.runtime.ExecutionRouteResolver;
import com.datagent.agentv2.workflow.runtime.QueryPlanValidator;
import com.datagent.agentv2.workflow.runtime.ResultVerifier;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 强类型数据分析 Workflow 的运行时装配。 */
@AutoConfiguration
@AutoConfigureAfter(StarRocksDataSourceConfiguration.class)
@EnableConfigurationProperties(DataAnalysisWorkflowProperties.class)
public class DataAnalysisWorkflowConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock dataAnalysisWorkflowClock() {
        return Clock.systemUTC();
    }

    @Bean
    QueryPlanValidator queryPlanValidator() {
        return new QueryPlanValidator();
    }

    @Bean
    ResultVerifier resultVerifier() {
        return new ResultVerifier();
    }

    @Bean
    @ConditionalOnBean(WorkflowEventPort.class)
    WorkflowRunRegistry workflowRunRegistry(WorkflowEventPort events, Clock clock) {
        return new WorkflowRunRegistry(events, clock, 1_000);
    }

    @Bean
    @ConditionalOnBean(WorkflowModelPort.class)
    ExecutionRouteResolver executionRouteResolver(WorkflowModelPort model) {
        return new ExecutionRouteResolver(model);
    }

    @Bean
    DataAnalysisWorkflowMetrics dataAnalysisWorkflowMetrics(MeterRegistry meterRegistry) {
        return new DataAnalysisWorkflowMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "data-agent.workflow", name = "enabled", havingValue = "true")
    @ConditionalOnBean({
        WorkflowModelPort.class,
        WorkflowFinalAnswerPort.class,
        WorkflowExecutionControlPort.class,
        WorkflowRunRegistry.class,
        SemanticResourceSearchService.class,
        StarRocksSqlPolicyValidator.class,
        StarRocksQueryGateway.class
    })
    DataAnalysisWorkflowEngine dataAnalysisWorkflowEngine(
            WorkflowModelPort model,
            SemanticResourceSearchService semanticSearch,
            StarRocksSqlPolicyValidator sqlPolicy,
            StarRocksQueryGateway queries,
            QueryPlanValidator planValidator,
            ResultVerifier resultVerifier,
            WorkflowRunRegistry registry,
            WorkflowFinalAnswerPort finalAnswers,
            WorkflowExecutionControlPort executionControl,
            DataAnalysisWorkflowProperties properties,
            StarRocksDataSourceProperties datasourceProperties,
            DataAnalysisWorkflowMetrics metrics,
            Clock clock) {
        return new DataAnalysisWorkflowEngine(
                model,
                semanticSearch,
                sqlPolicy,
                queries,
                planValidator,
                resultVerifier,
                registry,
                finalAnswers,
                executionControl,
                properties,
                datasourceProperties,
                metrics,
                clock);
    }
}
