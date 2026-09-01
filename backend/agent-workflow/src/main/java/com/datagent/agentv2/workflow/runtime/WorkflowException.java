package com.datagent.agentv2.workflow.runtime;

/** Workflow 收敛后的稳定失败。 */
public class WorkflowException extends RuntimeException {

    private final WorkflowFailureCode code;

    public WorkflowException(WorkflowFailureCode code, String message) {
        super(message);
        this.code = code;
    }

    public WorkflowException(WorkflowFailureCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public WorkflowFailureCode code() {
        return code;
    }
}
