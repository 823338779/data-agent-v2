package com.datagent.agentv2.datasource.semantic;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 启动期构建且运行期只读的完整语义目录。 */
public record SemanticCatalog(
        int version,
        List<SemanticDataset> datasets,
        List<SemanticMetric> metrics,
        List<SemanticRelationship> relationships,
        Map<String, SemanticDataset> datasetsById,
        Map<String, SemanticMetric> metricsById,
        Map<String, SemanticRelationship> relationshipsById) {

    public SemanticCatalog(
            int version,
            List<SemanticDataset> datasets,
            List<SemanticMetric> metrics,
            List<SemanticRelationship> relationships) {
        this(
                version,
                List.copyOf(datasets),
                List.copyOf(metrics),
                List.copyOf(relationships),
                index(datasets, SemanticDataset::id),
                index(metrics, SemanticMetric::id),
                index(relationships, SemanticRelationship::id));
    }

    public SemanticCatalog {
        datasets = List.copyOf(datasets);
        metrics = List.copyOf(metrics);
        relationships = List.copyOf(relationships);
        datasetsById = Map.copyOf(datasetsById);
        metricsById = Map.copyOf(metricsById);
        relationshipsById = Map.copyOf(relationshipsById);
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> id) {
        return values.stream().collect(Collectors.toUnmodifiableMap(id, Function.identity()));
    }
}
