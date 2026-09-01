package com.datagent.agentv2.datasource.semantic;

import java.util.List;

/** 数据集字段的业务语义及已校验物理映射。 */
public record SemanticField(
        String id,
        String column,
        String name,
        Role role,
        String dataType,
        String unit,
        List<String> synonyms,
        String description) {

    public SemanticField {
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
    }

    public enum Role {
        DIMENSION,
        MEASURE,
        TIME
    }
}
