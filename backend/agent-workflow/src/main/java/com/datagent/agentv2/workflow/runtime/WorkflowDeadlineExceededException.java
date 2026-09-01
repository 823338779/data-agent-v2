package com.datagent.agentv2.workflow.runtime;

/** Workflow 或外层 execution 截止时间已到。 */
public final class WorkflowDeadlineExceededException extends WorkflowException {
    public WorkflowDeadlineExceededException(String message) {
        super(WorkflowFailureCode.WORKFLOW_DEADLINE_EXCEEDED, message);
    }
}
