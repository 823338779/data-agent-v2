package com.datagent.agentv2.workflow.runtime;

import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryTaskResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 对查询终态和分析证据进行确定性验证。 */
public final class ResultVerifier {

    public void verifyResults(QueryPlan plan, List<QueryTaskResult> results) {
        if (results.size() != plan.tasks().size()) {
            throw invalid("并非所有 QueryTask 都有终态结果");
        }
        for (int index = 0; index < plan.tasks().size(); index++) {
            QueryTaskResult result = results.get(index);
            if (!plan.tasks().get(index).taskId().equals(result.task().taskId())
                    || result.result() == null) {
                throw invalid("查询结果与 QueryTask 顺序不一致");
            }
            if (result.result().columns() == null || result.result().rows() == null) {
                throw invalid("查询结果结构不完整");
            }
        }
    }

    public void verifyAnalysis(AnalysisResult analysis, List<QueryTaskResult> results) {
        if (analysis == null || analysis.summary() == null || analysis.summary().isBlank()) {
            throw new WorkflowValidationException(WorkflowFailureCode.ANALYSIS_FAILED, "分析摘要不能为空");
        }
        Map<String, Integer> rowCounts = new HashMap<>();
        results.forEach(
                result -> rowCounts.put(result.task().taskId(), result.result().rows().size()));
        analysis.findings()
                .forEach(
                        finding -> {
                            if (finding.statement() == null
                                    || finding.statement().isBlank()
                                    || finding.evidence().isEmpty()) {
                                throw new WorkflowValidationException(
                                        WorkflowFailureCode.ANALYSIS_FAILED, "每条分析结论必须包含证据");
                            }
                            finding.evidence()
                                    .forEach(
                                            evidence -> {
                                                Integer rowCount = rowCounts.get(evidence.taskId());
                                                if (rowCount == null
                                                        || evidence.rowIndexes().stream()
                                                                .anyMatch(
                                                                        row ->
                                                                                row == null
                                                                                        || row < 0
                                                                                        || row
                                                                                                >= rowCount)) {
                                                    throw new WorkflowValidationException(
                                                            WorkflowFailureCode.ANALYSIS_FAILED,
                                                            "分析证据无法定位到查询结果");
                                                }
                                            });
                        });
    }

    private static WorkflowValidationException invalid(String message) {
        return new WorkflowValidationException(
                WorkflowFailureCode.WORKFLOW_INTERNAL_ERROR, message);
    }
}
