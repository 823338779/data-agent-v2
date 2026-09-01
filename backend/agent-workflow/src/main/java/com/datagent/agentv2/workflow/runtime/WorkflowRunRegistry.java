package com.datagent.agentv2.workflow.runtime;

import com.datagent.agentv2.workflow.port.WorkflowEventPort;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;

/** 有界、非持久化的运行期 Registry，终态事件发送后立即清理。 */
@RequiredArgsConstructor
public final class WorkflowRunRegistry {

    private final WorkflowEventPort eventPort;
    private final Clock clock;
    private final int maxEventsPerRun;
    private final ConcurrentHashMap<String, RunState> byExecution = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RunState> byWorkflow = new ConcurrentHashMap<>();

    public WorkflowSnapshot open(WorkflowRunRequest request) {
        if (request == null
                || blank(request.executionId())
                || blank(request.conversationSessionId())) {
            throw new IllegalArgumentException("Workflow Run 身份不能为空");
        }
        String workflowId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        WorkflowSnapshot snapshot =
                new WorkflowSnapshot(
                        workflowId,
                        request.executionId(),
                        0,
                        WorkflowStatus.RUNNING,
                        WorkflowStep.DISCOVER_SEMANTIC_RESOURCES,
                        List.of(),
                        now,
                        now);
        RunState state = new RunState(request, snapshot);
        if (byExecution.putIfAbsent(request.executionId(), state) != null) {
            throw new IllegalStateException("同一 execution 已存在活动 Workflow");
        }
        byWorkflow.put(workflowId, state);
        return snapshot;
    }

    public WorkflowEvent transition(
            String workflowId,
            WorkflowEventType eventType,
            WorkflowStep step,
            WorkflowStatus status,
            WorkflowTaskRef task,
            WorkflowEventDetail detail) {
        RunState state = requireActive(workflowId);
        WorkflowEvent event;
        synchronized (state.lock) {
            ensureRunning(state.snapshot.status());
            ensureTransition(state.snapshot.currentStep(), step);
            if (state.snapshot.latestSequence() >= maxEventsPerRun) {
                throw new WorkflowException(
                        WorkflowFailureCode.WORKFLOW_INTERNAL_ERROR, "Workflow 事件数量超过服务端上限");
            }
            long sequence = state.snapshot.latestSequence() + 1;
            Instant now = clock.instant();
            List<WorkflowTaskSnapshot> tasks =
                    updateTask(state.snapshot.tasks(), task, step, detail);
            state.snapshot =
                    new WorkflowSnapshot(
                            workflowId,
                            state.request.executionId(),
                            sequence,
                            status,
                            step,
                            tasks,
                            state.snapshot.startedAt(),
                            now);
            event = event(state, sequence, eventType, step, status, task, detail, now);
        }
        eventPort.publish(event);
        return event;
    }

    public WorkflowEvent finish(
            String workflowId, WorkflowStatus terminalStatus, WorkflowEventDetail detail) {
        if (!terminalStatus.terminal()) {
            throw new IllegalArgumentException("finish 只接受终态");
        }
        RunState state = requireActive(workflowId);
        WorkflowEvent event;
        synchronized (state.lock) {
            ensureRunning(state.snapshot.status());
            long sequence = state.snapshot.latestSequence() + 1;
            Instant now = clock.instant();
            state.snapshot =
                    new WorkflowSnapshot(
                            workflowId,
                            state.request.executionId(),
                            sequence,
                            terminalStatus,
                            state.snapshot.currentStep(),
                            state.snapshot.tasks(),
                            state.snapshot.startedAt(),
                            now);
            WorkflowEventType type =
                    terminalStatus == WorkflowStatus.SUCCEEDED
                            ? WorkflowEventType.WORKFLOW_SUCCEEDED
                            : WorkflowEventType.WORKFLOW_FAILED;
            event =
                    event(
                            state,
                            sequence,
                            type,
                            state.snapshot.currentStep(),
                            terminalStatus,
                            null,
                            detail,
                            now);
        }
        try {
            eventPort.publish(event);
            return event;
        } finally {
            close(workflowId);
        }
    }

    public Optional<WorkflowSnapshot> snapshot(String tenantId, String conversationSessionId) {
        return byExecution.values().stream()
                .filter(
                        state ->
                                state.request.tenantId().equals(tenantId)
                                        && state.request
                                                .conversationSessionId()
                                                .equals(conversationSessionId))
                .map(state -> state.snapshot)
                .findFirst();
    }

    public boolean isActive(String executionId) {
        return byExecution.containsKey(executionId);
    }

    public void close(String workflowId) {
        RunState state = byWorkflow.remove(workflowId);
        if (state != null) {
            byExecution.remove(state.request.executionId(), state);
        }
    }

    private RunState requireActive(String workflowId) {
        RunState state = byWorkflow.get(workflowId);
        if (state == null) {
            throw new IllegalStateException("Workflow 不存在或已结束");
        }
        return state;
    }

    private static WorkflowEvent event(
            RunState state,
            long sequence,
            WorkflowEventType type,
            WorkflowStep step,
            WorkflowStatus status,
            WorkflowTaskRef task,
            WorkflowEventDetail detail,
            Instant now) {
        return new WorkflowEvent(
                state.request.tenantId(),
                state.request.conversationSessionId(),
                state.request.executionId(),
                state.snapshot.workflowId(),
                sequence,
                type,
                step,
                status,
                task,
                detail == null ? WorkflowEventDetail.empty() : detail,
                now);
    }

    private static List<WorkflowTaskSnapshot> updateTask(
            List<WorkflowTaskSnapshot> existing,
            WorkflowTaskRef task,
            WorkflowStep step,
            WorkflowEventDetail detail) {
        if (task == null) return existing;
        Map<String, WorkflowTaskSnapshot> tasks = new LinkedHashMap<>();
        existing.forEach(snapshot -> tasks.put(snapshot.task().taskId(), snapshot));
        WorkflowTaskSnapshot previous = tasks.get(task.taskId());
        tasks.put(
                task.taskId(),
                new WorkflowTaskSnapshot(
                        task,
                        step,
                        detail.sql() != null
                                ? detail.sql()
                                : previous == null ? null : previous.sql(),
                        detail.returnedRows() != null
                                ? detail.returnedRows()
                                : previous == null ? null : previous.returnedRows(),
                        detail.truncated() != null
                                ? detail.truncated()
                                : previous == null ? null : previous.truncated(),
                        detail.attempt() != null
                                ? detail.attempt()
                                : previous == null ? null : previous.attempt(),
                        detail.message() != null
                                ? detail.message()
                                : previous == null ? null : previous.message()));
        return List.copyOf(tasks.values());
    }

    private static void ensureTransition(WorkflowStep current, WorkflowStep next) {
        if (current == next) return;
        boolean allowed =
                switch (current) {
                    case DISCOVER_SEMANTIC_RESOURCES -> next == WorkflowStep.BUILD_QUERY_PLAN;
                    case BUILD_QUERY_PLAN -> next == WorkflowStep.VALIDATE_QUERY_PLAN;
                    case VALIDATE_QUERY_PLAN ->
                            next == WorkflowStep.BUILD_QUERY_PLAN
                                    || next == WorkflowStep.GENERATE_SQL;
                    case GENERATE_SQL -> next == WorkflowStep.VALIDATE_SQL;
                    case VALIDATE_SQL ->
                            next == WorkflowStep.GENERATE_SQL || next == WorkflowStep.EXPLAIN_SQL;
                    case EXPLAIN_SQL ->
                            next == WorkflowStep.GENERATE_SQL || next == WorkflowStep.EXECUTE_QUERY;
                    case EXECUTE_QUERY ->
                            next == WorkflowStep.GENERATE_SQL
                                    || next == WorkflowStep.VERIFY_RESULTS;
                    case VERIFY_RESULTS -> next == WorkflowStep.ANALYZE_RESULTS;
                    case ANALYZE_RESULTS -> next == WorkflowStep.FINALIZE_RESPONSE;
                    case FINALIZE_RESPONSE -> false;
                };
        if (!allowed) {
            throw new IllegalStateException("非法 Workflow 状态迁移: " + current + " -> " + next);
        }
    }

    private static void ensureRunning(WorkflowStatus status) {
        if (status.terminal()) {
            throw new IllegalStateException("Workflow 已进入终态");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static final class RunState {
        private final Object lock = new Object();
        private final WorkflowRunRequest request;
        private volatile WorkflowSnapshot snapshot;

        private RunState(WorkflowRunRequest request, WorkflowSnapshot snapshot) {
            this.request = request;
            this.snapshot = snapshot;
        }
    }
}
