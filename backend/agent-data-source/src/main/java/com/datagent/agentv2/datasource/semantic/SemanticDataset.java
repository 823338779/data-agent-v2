package com.datagent.agentv2.datasource.semantic;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 一个可授权查询的数据集，字段均已与 StarRocks 元数据完成核对。 */
public record SemanticDataset(
        String id,
        String table,
        String name,
        String description,
        List<String> synonyms,
        String defaultTimeField,
        List<SemanticField> fields,
        Map<String, SemanticField> fieldsById) {

    public SemanticDataset(
            String id,
            String table,
            String name,
            String description,
            List<String> synonyms,
            String defaultTimeField,
            List<SemanticField> fields) {
        this(
                id,
                table,
                name,
                description,
                synonyms == null ? List.of() : List.copyOf(synonyms),
                defaultTimeField,
                fields == null ? List.of() : List.copyOf(fields),
                index(fields));
    }

    public SemanticDataset {
        synonyms = List.copyOf(synonyms);
        fields = List.copyOf(fields);
        fieldsById = Map.copyOf(fieldsById);
    }

    private static Map<String, SemanticField> index(List<SemanticField> fields) {
        if (fields == null) {
            return Map.of();
        }
        return fields.stream()
                .collect(Collectors.toUnmodifiableMap(SemanticField::id, Function.identity()));
    }
}
