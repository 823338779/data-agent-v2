package com.datagent.agentv2.workflow.runtime;

/** 用户取消当前 execution 后立即停止后续副作用。 */
public final class WorkflowCancelledException extends WorkflowException {
    public WorkflowCancelledException(String message) {
        super(WorkflowFailureCode.WORKFLOW_CANCELLED, message);
    }
}
