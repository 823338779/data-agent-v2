package com.datagent.agentv2.workflow.observability;

import com.datagent.agentv2.workflow.runtime.WorkflowFailureCode;
import com.datagent.agentv2.workflow.runtime.WorkflowStep;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

/** 仅使用固定低基数标签记录 Workflow 指标，严禁写入 SQL 或业务身份。 */
@RequiredArgsConstructor
public final class DataAnalysisWorkflowMetrics {

    private final MeterRegistry registry;

    public void workflowFinished(String outcome) {
        registry.counter("data_agent.workflow.total", "outcome", outcome).increment();
    }

    public void stepCompleted(WorkflowStep step, Duration duration) {
        registry.timer("data_agent.workflow.step.duration", "step", step.value()).record(duration);
    }

    public void retry(WorkflowStep step, String reason) {
        registry.counter("data_agent.workflow.retry.total", "step", step.value(), "reason", reason)
                .increment();
    }

    public void failure(WorkflowFailureCode reason) {
        registry.counter("data_agent.workflow.failure.total", "reason", reason.value()).increment();
    }
}
