package com.datagent.agentv2.datasource.semantic;

import com.datagent.agentv2.datasource.semantic.SemanticCatalogDocument.DatasetDocument;
import com.datagent.agentv2.datasource.semantic.SemanticCatalogDocument.FieldDocument;
import com.datagent.agentv2.datasource.semantic.SemanticCatalogDocument.MetricDocument;
import com.datagent.agentv2.datasource.semantic.SemanticCatalogDocument.RelationshipDocument;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将 YAML 与真实元数据交叉校验，并生成不可变运行时目录。 */
public final class SemanticCatalogValidator {

    public SemanticCatalog validate(
            SemanticCatalogDocument document, StarRocksMetadataReader.MetadataSnapshot metadata) {
        if (document == null || document.version() != 1) {
            throw new IllegalStateException("语义配置 version 必须为 1");
        }
        List<DatasetDocument> datasets = nonNull(document.datasets());
        List<MetricDocument> metrics = nonNull(document.metrics());
        List<RelationshipDocument> relationships = nonNull(document.relationships());
        if (datasets.isEmpty()) {
            throw new IllegalStateException("语义配置至少需要一个 dataset");
        }
        requireUnique(datasets.stream().map(DatasetDocument::id).toList(), "dataset");
        requireUnique(metrics.stream().map(MetricDocument::id).toList(), "metric");
        requireUnique(
                relationships.stream().map(RelationshipDocument::id).toList(), "relationship");

        List<SemanticDataset> runtimeDatasets =
                datasets.stream().map(dataset -> validateDataset(dataset, metadata)).toList();
        var catalogWithoutMetrics = new SemanticCatalog(1, runtimeDatasets, List.of(), List.of());
        List<SemanticMetric> runtimeMetrics =
                metrics.stream()
                        .map(metric -> validateMetric(metric, catalogWithoutMetrics))
                        .toList();
        List<SemanticRelationship> runtimeRelationships =
                relationships.stream()
                        .map(
                                relationship ->
                                        validateRelationship(relationship, catalogWithoutMetrics))
                        .toList();
        return new SemanticCatalog(1, runtimeDatasets, runtimeMetrics, runtimeRelationships);
    }

    private SemanticDataset validateDataset(
            DatasetDocument document, StarRocksMetadataReader.MetadataSnapshot metadata) {
        requireText(document.id(), "dataset.id");
        requireText(document.table(), document.id() + ".table");
        var table = metadata.tables().get(normalize(document.table()));
        if (table == null) {
            throw new IllegalStateException("语义数据集引用了不存在的表: " + document.id());
        }
        List<FieldDocument> fields = nonNull(document.fields());
        requireUnique(fields.stream().map(FieldDocument::id).toList(), document.id() + ".field");
        List<SemanticField> runtimeFields =
                fields.stream().map(field -> validateField(document.id(), field, table)).toList();
        SemanticDataset dataset =
                new SemanticDataset(
                        document.id(),
                        table.name(),
                        document.name(),
                        document.description(),
                        document.synonyms(),
                        document.defaultTimeField(),
                        runtimeFields);
        if (document.defaultTimeField() != null
                && !document.defaultTimeField().isBlank()
                && !dataset.fieldsById().containsKey(document.defaultTimeField())) {
            throw new IllegalStateException(
                    "默认时间字段不存在: " + document.id() + "." + document.defaultTimeField());
        }
        return dataset;
    }

    private SemanticField validateField(
            String datasetId, FieldDocument document, StarRocksMetadataReader.TableMetadata table) {
        requireText(document.id(), datasetId + ".field.id");
        requireText(document.column(), datasetId + "." + document.id() + ".column");
        var column = table.columns().get(normalize(document.column()));
        if (column == null) {
            throw new IllegalStateException("语义字段不存在: " + datasetId + "." + document.id());
        }
        SemanticField.Role role;
        try {
            role = SemanticField.Role.valueOf(document.role().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("字段角色不合法: " + datasetId + "." + document.id(), failure);
        }
        return new SemanticField(
                document.id(),
                column.name(),
                document.name(),
                role,
                document.dataType() == null ? column.type() : document.dataType(),
                document.unit(),
                document.synonyms(),
                firstText(document.description(), column.comment()));
    }

    private SemanticMetric validateMetric(MetricDocument document, SemanticCatalog catalog) {
        requireText(document.id(), "metric.id");
        SemanticDataset dataset = catalog.datasetsById().get(document.dataset());
        if (dataset == null) {
            throw new IllegalStateException("指标引用了未知数据集: " + document.id());
        }
        if (!dataset.fieldsById().containsKey(document.field())) {
            throw new IllegalStateException("指标引用了未知字段: " + document.id() + "." + document.field());
        }
        List<SemanticMetric.MetricFilter> filters =
                nonNull(document.filters()).stream()
                        .map(
                                filter -> {
                                    if (!dataset.fieldsById().containsKey(filter.field())) {
                                        throw new IllegalStateException(
                                                "指标过滤引用了未知字段: "
                                                        + document.id()
                                                        + "."
                                                        + filter.field());
                                    }
                                    return new SemanticMetric.MetricFilter(
                                            filter.field(), filter.operator(), filter.value());
                                })
                        .toList();
        try {
            return new SemanticMetric(
                    document.id(),
                    document.name(),
                    document.dataset(),
                    document.description(),
                    SemanticMetric.Aggregation.valueOf(
                            document.aggregation().toUpperCase(Locale.ROOT)),
                    document.field(),
                    filters,
                    document.unit(),
                    document.synonyms());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("指标聚合方式不合法: " + document.id(), failure);
        }
    }

    private SemanticRelationship validateRelationship(
            RelationshipDocument document, SemanticCatalog catalog) {
        SemanticDataset left = requireDataset(catalog, document.leftDataset(), document.id());
        SemanticDataset right = requireDataset(catalog, document.rightDataset(), document.id());
        if (!left.fieldsById().containsKey(document.leftField())
                || !right.fieldsById().containsKey(document.rightField())) {
            throw new IllegalStateException("关系引用了未知字段: " + document.id());
        }
        try {
            return new SemanticRelationship(
                    document.id(),
                    document.leftDataset(),
                    document.leftField(),
                    document.rightDataset(),
                    document.rightField(),
                    SemanticRelationship.JoinType.valueOf(
                            document.joinType().toUpperCase(Locale.ROOT)),
                    document.cardinality());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("关系类型不合法: " + document.id(), failure);
        }
    }

    private static SemanticDataset requireDataset(
            SemanticCatalog catalog, String id, String relationshipId) {
        SemanticDataset dataset = catalog.datasetsById().get(id);
        if (dataset == null) {
            throw new IllegalStateException("关系引用了未知数据集: " + relationshipId);
        }
        return dataset;
    }

    private static void requireUnique(List<String> values, String kind) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            requireText(value, kind + ".id");
            if (!seen.add(value)) {
                throw new IllegalStateException("语义资源 ID 重复: " + value);
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("语义字段不能为空: " + field);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
