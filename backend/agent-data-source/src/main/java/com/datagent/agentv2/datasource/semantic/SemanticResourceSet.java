package com.datagent.agentv2.datasource.semantic;

import java.util.List;

/** 一次问题召回到的有界语义快照，后续 QueryPlan 只能引用其中资源。 */
public record SemanticResourceSet(
        List<SemanticDataset> datasets,
        List<SemanticFieldRef> fields,
        List<SemanticMetric> metrics,
        List<SemanticRelationship> relationships) {

    public SemanticResourceSet {
        datasets = List.copyOf(datasets);
        fields = List.copyOf(fields);
        metrics = List.copyOf(metrics);
        relationships = List.copyOf(relationships);
    }

    /** 字段必须连同所属数据集返回，避免不同表同名字段产生歧义。 */
    public record SemanticFieldRef(String datasetId, SemanticField field) {}
}
