package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 有界查询结果经过分析后的结构化输出。 */
public record AnalysisResult(String summary, List<AnalysisFinding> findings, List<String> caveats) {
    public AnalysisResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }
}
