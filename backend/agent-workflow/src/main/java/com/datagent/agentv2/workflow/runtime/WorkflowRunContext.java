package com.datagent.agentv2.workflow.runtime;

import com.datagent.agentv2.datasource.semantic.SemanticResourceSet;
import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryTaskResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 单线程状态机持有的受控可变上下文，不对外暴露通用 setter。 */
public final class WorkflowRunContext {

    private final String workflowId;
    private final WorkflowRunRequest request;
    private final Instant hardDeadlineAt;
    private WorkflowStep currentStep;
    private SemanticResourceSet semanticResources;
    private QueryPlan queryPlan;
    private final List<QueryTaskResult> taskResults = new ArrayList<>();
    private AnalysisResult analysisResult;

    WorkflowRunContext(String workflowId, WorkflowRunRequest request, Instant hardDeadlineAt) {
        this.workflowId = workflowId;
        this.request = request;
        this.hardDeadlineAt = hardDeadlineAt;
        this.currentStep = WorkflowStep.DISCOVER_SEMANTIC_RESOURCES;
    }

    public String workflowId() {
        return workflowId;
    }

    public WorkflowRunRequest request() {
        return request;
    }

    public Instant hardDeadlineAt() {
        return hardDeadlineAt;
    }

    public WorkflowStep currentStep() {
        return currentStep;
    }

    public SemanticResourceSet semanticResources() {
        return semanticResources;
    }

    public QueryPlan queryPlan() {
        return queryPlan;
    }

    public List<QueryTaskResult> taskResults() {
        return List.copyOf(taskResults);
    }

    public AnalysisResult analysisResult() {
        return analysisResult;
    }

    void moveTo(WorkflowStep step) {
        this.currentStep = step;
    }

    void useSemanticResources(SemanticResourceSet resources) {
        this.semanticResources = resources;
    }

    void useQueryPlan(QueryPlan plan) {
        this.queryPlan = plan;
    }

    void addTaskResult(QueryTaskResult result) {
        this.taskResults.add(result);
    }

    void useAnalysisResult(AnalysisResult analysis) {
        this.analysisResult = analysis;
    }
}
