package com.datagent.agentv2.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowRunRegistryTest {

    @Test
    void publishesMonotonicEventsAndRemovesTerminalSnapshot() {
        List<WorkflowEvent> events = new ArrayList<>();
        WorkflowRunRegistry registry =
                new WorkflowRunRegistry(
                        events::add,
                        Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC),
                        20);
        WorkflowRunRequest request = request("execution-1");
        WorkflowSnapshot opened = registry.open(request);
        WorkflowEvent started =
                registry.transition(
                        opened.workflowId(),
                        WorkflowEventType.STEP_STARTED,
                        WorkflowStep.DISCOVER_SEMANTIC_RESOURCES,
                        WorkflowStatus.RUNNING,
                        null,
                        WorkflowEventDetail.empty());
        assertThat(started.sequence()).isEqualTo(1);
        assertThat(registry.snapshot("tenant", "session"))
                .get()
                .extracting(WorkflowSnapshot::currentStep)
                .isEqualTo(WorkflowStep.DISCOVER_SEMANTIC_RESOURCES);
        registry.finish(opened.workflowId(), WorkflowStatus.SUCCEEDED, WorkflowEventDetail.empty());
        assertThat(registry.snapshot("tenant", "session")).isEmpty();
        assertThat(events).extracting(WorkflowEvent::sequence).containsExactly(1L, 2L);
    }

    @Test
    void rejectsDuplicateExecutionAndIllegalTransition() {
        WorkflowRunRegistry registry =
                new WorkflowRunRegistry(ignored -> {}, Clock.systemUTC(), 20);
        WorkflowSnapshot opened = registry.open(request("execution-1"));
        assertThatThrownBy(() -> registry.open(request("execution-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                registry.transition(
                                        opened.workflowId(),
                                        WorkflowEventType.STEP_STARTED,
                                        WorkflowStep.EXECUTE_QUERY,
                                        WorkflowStatus.RUNNING,
                                        null,
                                        WorkflowEventDetail.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static WorkflowRunRequest request(String executionId) {
        return new WorkflowRunRequest(
                "tenant",
                "session",
                executionId,
                1,
                "分析销售额",
                List.of(),
                Instant.now().plusSeconds(600));
    }
}
