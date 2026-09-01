package com.datagent.agentv2.datasource.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SemanticCatalogValidatorTest {

    @Test
    void loadsAndValidatesYamlAgainstMetadata() {
        var document =
                new SemanticCatalogLoader()
                        .load(new ClassPathResource("semantic/valid-starrocks.yaml"));
        SemanticCatalog catalog = new SemanticCatalogValidator().validate(document, metadata());
        assertThat(catalog.datasetsById()).containsKey("orders");
        assertThat(catalog.metricsById()).containsKey("paid_sales");
    }

    @Test
    void rejectsFieldMissingFromMetadata() {
        var field =
                new SemanticCatalogDocument.FieldDocument(
                        "missing",
                        "missing_column",
                        "缺失",
                        "dimension",
                        "string",
                        null,
                        List.of(),
                        null);
        var dataset =
                new SemanticCatalogDocument.DatasetDocument(
                        "orders", "fact_orders", "订单", null, List.of(), null, List.of(field));
        var document = new SemanticCatalogDocument(1, List.of(dataset), List.of(), List.of());
        assertThatThrownBy(() -> new SemanticCatalogValidator().validate(document, metadata()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders.missing");
    }

    private static StarRocksMetadataReader.MetadataSnapshot metadata() {
        Map<String, StarRocksMetadataReader.ColumnMetadata> columns = new LinkedHashMap<>();
        columns.put(
                "paid_amount",
                new StarRocksMetadataReader.ColumnMetadata("paid_amount", "DECIMAL", "实付金额"));
        columns.put(
                "region_code",
                new StarRocksMetadataReader.ColumnMetadata("region_code", "VARCHAR", "地区"));
        columns.put(
                "paid_at",
                new StarRocksMetadataReader.ColumnMetadata("paid_at", "DATETIME", "支付时间"));
        return new StarRocksMetadataReader.MetadataSnapshot(
                Map.of(
                        "fact_orders",
                        new StarRocksMetadataReader.TableMetadata(
                                "fact_orders", "订单事实表", columns)));
    }
}
