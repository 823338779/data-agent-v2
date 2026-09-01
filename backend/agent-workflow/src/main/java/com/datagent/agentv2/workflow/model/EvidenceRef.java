package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 分析结论的数据证据；行号从 0 开始，必须落在对应任务结果范围内。 */
public record EvidenceRef(String taskId, List<Integer> rowIndexes) {
    public EvidenceRef {
        rowIndexes = rowIndexes == null ? List.of() : List.copyOf(rowIndexes);
    }
}
