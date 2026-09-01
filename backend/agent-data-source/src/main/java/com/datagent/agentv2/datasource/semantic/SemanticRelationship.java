package com.datagent.agentv2.datasource.semantic;

/** 两个数据集间唯一允许的结构化关联边。 */
public record SemanticRelationship(
        String id,
        String leftDataset,
        String leftField,
        String rightDataset,
        String rightField,
        JoinType joinType,
        String cardinality) {

    public enum JoinType {
        INNER,
        LEFT
    }
}
