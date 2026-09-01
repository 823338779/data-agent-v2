package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 一条必须附带可定位数据证据的分析结论。 */
public record AnalysisFinding(String statement, List<EvidenceRef> evidence) {
    public AnalysisFinding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
