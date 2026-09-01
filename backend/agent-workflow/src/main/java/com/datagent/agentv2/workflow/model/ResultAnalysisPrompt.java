package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 结果分析节点的有界输入。 */
public record ResultAnalysisPrompt(
        String analysisGoal, List<QueryTaskResult> taskResults, int maxInputBytes) {
    public ResultAnalysisPrompt {
        taskResults = List.copyOf(taskResults);
    }
}
