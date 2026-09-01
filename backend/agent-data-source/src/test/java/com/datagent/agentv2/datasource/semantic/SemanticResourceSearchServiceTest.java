package com.datagent.agentv2.datasource.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticResourceSearchServiceTest {

    @Test
    void exactSynonymAndOwningDatasetAreReturnedDeterministically() {
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
        SemanticDataset orders =
                new SemanticDataset(
                        "orders", "fact_orders", "订单", "业务订单", List.of(), null, List.of(amount));
        SemanticMetric sales =
                new SemanticMetric(
                        "paid_sales",
                        "销售额",
                        "orders",
                        "销售额合计",
                        SemanticMetric.Aggregation.SUM,
                        "paid_amount",
                        List.of(),
                        "CNY",
                        List.of("成交额"));
        SemanticResourceSet result =
                new SemanticResourceSearchService(
                                new SemanticCatalog(1, List.of(orders), List.of(sales), List.of()))
                        .search("销售额", 10);
        assertThat(result.metrics()).extracting(SemanticMetric::id).containsExactly("paid_sales");
        assertThat(result.datasets()).extracting(SemanticDataset::id).containsExactly("orders");
        assertThat(result.fields())
                .extracting(ref -> ref.field().id())
                .containsExactly("paid_amount");
    }
}
