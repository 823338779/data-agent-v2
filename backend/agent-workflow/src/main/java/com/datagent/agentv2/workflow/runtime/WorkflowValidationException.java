package com.datagent.agentv2.workflow.runtime;

/** QueryPlan、结果或分析证据不满足确定性契约。 */
public final class WorkflowValidationException extends WorkflowException {
    public WorkflowValidationException(WorkflowFailureCode code, String message) {
        super(code, message);
    }
}
