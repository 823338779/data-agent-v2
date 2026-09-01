package com.datagent.agentv2.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.datasource.config.StarRocksDataSourceProperties;
import com.datagent.agentv2.datasource.query.ExplainResult;
import com.datagent.agentv2.datasource.query.ResultColumn;
import com.datagent.agentv2.datasource.query.StarRocksQueryGateway;
import com.datagent.agentv2.datasource.query.StarRocksSqlPolicyValidator;
import com.datagent.agentv2.datasource.query.TabularQueryResult;
import com.datagent.agentv2.datasource.semantic.SemanticCatalog;
import com.datagent.agentv2.datasource.semantic.SemanticDataset;
import com.datagent.agentv2.datasource.semantic.SemanticField;
import com.datagent.agentv2.datasource.semantic.SemanticMetric;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSearchService;
import com.datagent.agentv2.workflow.config.DataAnalysisWorkflowProperties;
import com.datagent.agentv2.workflow.model.AnalysisFinding;
import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.EvidenceRef;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.GeneratedSql;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryPlanPrompt;
import com.datagent.agentv2.workflow.model.QueryTask;
import com.datagent.agentv2.workflow.model.ResultAnalysisPrompt;
import com.datagent.agentv2.workflow.model.RoutePrompt;
import com.datagent.agentv2.workflow.model.SqlGenerationPrompt;
import com.datagent.agentv2.workflow.observability.DataAnalysisWorkflowMetrics;
import com.datagent.agentv2.workflow.port.WorkflowModelPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.unit.DataSize;

class DataAnalysisWorkflowEngineTest {

    @Test
    void executesOneTaskThroughFixedStateMachineAndPublishesSqlOnlyAfterExplain() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);
        List<WorkflowEvent> events = new ArrayList<>();
        WorkflowRunRegistry registry = new WorkflowRunRegistry(events::add, clock, 100);
        StarRocksQueryGateway gateway = mock(StarRocksQueryGateway.class);
        when(gateway.explain(any())).thenReturn(new ExplainResult(List.of("PLAN"), 5));
        when(gateway.execute(any()))
                .thenReturn(
                        new TabularQueryResult(
                                List.of(
                                        new ResultColumn("region_code", "VARCHAR"),
                                        new ResultColumn("sales", "DECIMAL")),
                                List.of(List.of("CN-EAST", 120)),
                                false,
                                1,
                                8));
        AtomicBoolean completed = new AtomicBoolean();
        DataAnalysisWorkflowEngine engine =
                new DataAnalysisWorkflowEngine(
                        new SuccessfulModel(),
                        new SemanticResourceSearchService(catalog()),
                        new StarRocksSqlPolicyValidator(),
                        gateway,
                        new QueryPlanValidator(),
                        new ResultVerifier(),
                        registry,
                        (request, analysis) -> completed.set(true),
                        (request, deadline) -> {
                            if (!clock.instant().isBefore(deadline))
                                throw new WorkflowDeadlineExceededException("超时");
                        },
                        DataAnalysisWorkflowProperties.defaults(),
                        datasourceProperties(),
                        new DataAnalysisWorkflowMetrics(new SimpleMeterRegistry()),
                        clock);

        engine.execute(
                new WorkflowRunRequest(
                        "tenant",
                        "session",
                        "execution",
                        1,
                        "按地区分析销售额",
                        List.of(),
                        clock.instant().plusSeconds(600)));

        assertThat(completed).isTrue();
        assertThat(registry.isActive("execution")).isFalse();
        assertThat(events)
                .extracting(WorkflowEvent::eventType)
                .contains(WorkflowEventType.WORKFLOW_STARTED, WorkflowEventType.WORKFLOW_SUCCEEDED);
        assertThat(events.stream().filter(event -> event.detail().sql() != null).toList())
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.step()).isEqualTo(WorkflowStep.EXPLAIN_SQL);
                            assertThat(event.eventType())
                                    .isEqualTo(WorkflowEventType.STEP_SUCCEEDED);
                            assertThat(event.detail().sql()).contains("fact_orders");
                        });
    }

    private static SemanticCatalog catalog() {
        SemanticField amount =
                new SemanticField(
                        "paid_amount",
                        "paid_amount",
                        "实付金额",
                        SemanticField.Role.MEASURE,
                        "decimal",
                        "CNY",
                        List.of("销售额"),
                        null);
        SemanticField region =
                new SemanticField(
                        "region",
                        "region_code",
                        "地区",
                        SemanticField.Role.DIMENSION,
                        "string",
                        null,
                        List.of(),
                        null);
        SemanticDataset orders =
                new SemanticDataset(
                        "orders",
                        "fact_orders",
                        "订单",
                        "业务订单",
                        List.of(),
                        null,
                        List.of(amount, region));
        SemanticMetric metric =
                new SemanticMetric(
                        "paid_sales",
                        "销售额",
                        "orders",
                        "销售额合计",
                        SemanticMetric.Aggregation.SUM,
                        "paid_amount",
                        List.of(),
                        "CNY",
                        List.of());
        return new SemanticCatalog(1, List.of(orders), List.of(metric), List.of());
    }

    private static StarRocksDataSourceProperties datasourceProperties() {
        return new StarRocksDataSourceProperties(
                "jdbc:starrocks://unused/default_catalog.analytics",
                "user",
                "secret",
                "default_catalog",
                "analytics",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                1000,
                DataSize.ofMegabytes(4),
                new ByteArrayResource(new byte[0]));
    }

    private static final class SuccessfulModel implements WorkflowModelPort {
        @Override
        public ExecutionRoute route(RoutePrompt prompt) {
            return ExecutionRoute.DATA_ANALYSIS_WORKFLOW;
        }

        @Override
        public QueryPlan buildQueryPlan(QueryPlanPrompt prompt) {
            QueryTask task =
                    new QueryTask(
                            "regional-sales",
                            "统计地区销售额",
                            List.of("orders", "paid_sales", "region"),
                            List.of("paid_sales"),
                            List.of("region"),
                            List.of(),
                            null,
                            List.of(),
                            100);
            return new QueryPlan("分析地区销售额", List.of(task));
        }

        @Override
        public GeneratedSql generateSql(SqlGenerationPrompt prompt) {
            return new GeneratedSql(
                    prompt.task().taskId(),
                    "select region_code, sum(paid_amount) as sales from fact_orders group by region_code");
        }

        @Override
        public AnalysisResult analyzeResults(ResultAnalysisPrompt prompt) {
            return new AnalysisResult(
                    "华东销售额为 120",
                    List.of(
                            new AnalysisFinding(
                                    "华东销售额为 120",
                                    List.of(new EvidenceRef("regional-sales", List.of(0))))),
                    List.of());
        }
    }
}
