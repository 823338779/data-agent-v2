package com.datagent.agentv2.datasource.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 语义 YAML 的反序列化模型；只有通过校验后才会转换为运行时目录。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SemanticCatalogDocument(
        int version,
        List<DatasetDocument> datasets,
        List<MetricDocument> metrics,
        List<RelationshipDocument> relationships) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DatasetDocument(
            String id,
            String table,
            String name,
            String description,
            List<String> synonyms,
            @JsonProperty("default-time-field") String defaultTimeField,
            List<FieldDocument> fields) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FieldDocument(
            String id,
            String column,
            String name,
            String role,
            @JsonProperty("data-type") String dataType,
            String unit,
            List<String> synonyms,
            String description) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MetricDocument(
            String id,
            String name,
            String dataset,
            String description,
            String aggregation,
            String field,
            List<FilterDocument> filters,
            String unit,
            List<String> synonyms) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FilterDocument(String field, String operator, Object value) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RelationshipDocument(
            String id,
            @JsonProperty("left-dataset") String leftDataset,
            @JsonProperty("left-field") String leftField,
            @JsonProperty("right-dataset") String rightDataset,
            @JsonProperty("right-field") String rightField,
            @JsonProperty("join-type") String joinType,
            String cardinality) {}
}
