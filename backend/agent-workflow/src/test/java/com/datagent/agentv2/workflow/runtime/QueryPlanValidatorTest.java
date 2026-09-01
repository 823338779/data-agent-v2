package com.datagent.agentv2.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.datasource.semantic.SemanticDataset;
import com.datagent.agentv2.datasource.semantic.SemanticField;
import com.datagent.agentv2.datasource.semantic.SemanticMetric;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSet;
import com.datagent.agentv2.workflow.config.DataAnalysisWorkflowProperties;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryTask;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryPlanValidatorTest {

    @Test
    void buildsPhysicalScopeFromRecalledBusinessResources() {
        SemanticResourceSet resources = resources();
        QueryTask task = task(List.of("orders", "paid_sales", "region"), List.of("paid_sales"));
        var validated =
                new QueryPlanValidator()
                        .validate(
                                new QueryPlan("分析地区销售额", List.of(task)),
                                resources,
                                DataAnalysisWorkflowProperties.defaults(),
                                1000);
        assertThat(validated.accessScopes().get("regional-sales").allowedTables())
                .containsExactly("fact_orders");
        assertThat(
                        validated
                                .accessScopes()
                                .get("regional-sales")
                                .allowedColumnsByTable()
                                .get("fact_orders"))
                .contains("region_code", "paid_amount");
    }

    @Test
    void rejectsResourceOutsideRecallSnapshot() {
        QueryTask task = task(List.of("orders", "unknown"), List.of("unknown"));
        assertThatThrownBy(
                        () ->
                                new QueryPlanValidator()
                                        .validate(
                                                new QueryPlan("分析", List.of(task)),
                                                resources(),
                                                DataAnalysisWorkflowProperties.defaults(),
                                                1000))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("unknown");
    }

    static SemanticResourceSet resources() {
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
                        List.of("交易"),
                        null,
                        List.of(amount, region));
        SemanticMetric sales =
                new SemanticMetric(
                        "paid_sales",
                        "销售额",
                        "orders",
                        "实付金额合计",
                        SemanticMetric.Aggregation.SUM,
                        "paid_amount",
                        List.of(),
                        "CNY",
                        List.of());
        return new SemanticResourceSet(
                List.of(orders),
                List.of(new SemanticResourceSet.SemanticFieldRef("orders", region)),
                List.of(sales),
                List.of());
    }

    static QueryTask task(List<String> resourceIds, List<String> metrics) {
        return new QueryTask(
                "regional-sales",
                "统计地区销售额",
                resourceIds,
                metrics,
                List.of("region"),
                List.of(),
                null,
                List.of(),
                100);
    }
}
