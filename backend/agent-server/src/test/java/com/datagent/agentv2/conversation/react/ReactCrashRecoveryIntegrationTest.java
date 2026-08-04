package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReactCrashRecoveryIntegrationTest {

    private static final AgentExecutionPort.ClaimedAgentExecution OWNED =
            new AgentExecutionPort.ClaimedAgentExecution("tenant", "run", "new-owner", 2L);
    private static final String ASSISTANT_ID = "assistant-center";
    private static final String PAYLOAD_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private final AgentExecutionService agentExecutions = mock(AgentExecutionService.class);
    private final ModelInvocationService modelInvocations = mock(ModelInvocationService.class);
    private final ToolExecutionService toolExecutions = mock(ToolExecutionService.class);
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final ReactRecoveryPlanner planner =
            new ReactRecoveryPlanner(agentExecutions, modelInvocations, toolExecutions, messages);

    @Test
    void abandonsStreamingTurnAndRetriesFromItsImmutableInputBoundary() {
        stubOwnedExecution();
        ModelInvocationEntity streaming = modelInvocation("streaming", 1L, 4L);
        when(modelInvocations.findByExecutionId("run")).thenReturn(List.of(streaming));
        when(modelInvocations.abandonRecovered(
                        eq("run"), eq(1L), eq(1L), eq(0L), eq("new-owner"), eq(2L), any()))
                .thenReturn(true);

        ReactRecoveryPlanner.RecoveryPlan plan = planner.recover(OWNED);

        assertThat(plan)
                .isEqualTo(
                        new ReactRecoveryPlanner.RecoveryPlan(
                                ReactRecoveryPlanner.Action.RETRY_MODEL, 1L, 4L));
        verify(modelInvocations)
                .abandonRecovered(
                        eq("run"), eq(1L), eq(1L), eq(0L), eq("new-owner"), eq(2L), any());
    }

    @Test
    void resumesPreparedAndExecutingToolsWithoutStartingAnotherModelFirst() {
        stubOwnedExecution();
        when(modelInvocations.findByExecutionId("run"))
                .thenReturn(List.of(modelInvocation("completed", 1L, 4L)));
        when(toolExecutions.findByTurn("run", 1L))
                .thenReturn(List.of(execution(1L, "prepared"), execution(2L, "executing")));

        assertThat(planner.recover(OWNED).action())
                .isEqualTo(ReactRecoveryPlanner.Action.EXECUTE_TOOLS);
    }

    @Test
    void skipsTerminalExecutionsOnlyWhenTheirBoundProcessItemsAreTerminal() {
        stubOwnedExecution();
        ToolExecutionEntity succeeded = execution(1L, "succeeded");
        ToolExecutionEntity failed = execution(2L, "failed");
        when(modelInvocations.findByExecutionId("run"))
                .thenReturn(List.of(modelInvocation("completed", 1L, 4L)));
        when(toolExecutions.findByTurn("run", 1L)).thenReturn(List.of(succeeded, failed));
        ConversationMessage assistant = assistant(succeeded, failed);
        when(messages.findByConversationMessageId(ASSISTANT_ID)).thenReturn(assistant);

        assertThat(planner.recover(OWNED).action())
                .isEqualTo(ReactRecoveryPlanner.Action.CONTINUE_MODEL);

        assistant.setThinkingProcess(List.of(terminalItem(succeeded)));
        assertThatThrownBy(() -> planner.recover(OWNED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Terminal tool execution is missing its process item result");
    }

    private void stubOwnedExecution() {
        AgentExecution run = new AgentExecution();
        run.setExecutionId("run");
        run.setStatus("running");
        run.setOwnerInstanceId("new-owner");
        run.setExecutionAttemptNumber(2L);
        when(agentExecutions.lockById("run")).thenReturn(run);
    }

    private static ModelInvocationEntity modelInvocation(
            String status, long sequence, long boundary) {
        ModelInvocationEntity modelInvocation = new ModelInvocationEntity();
        modelInvocation.setExecutionId("run");
        modelInvocation.setStatus(status);
        modelInvocation.setModelInvocationSequence(sequence);
        modelInvocation.setExecutionAttemptNumber(1L);
        modelInvocation.setInputUpToConversationMessageSequence(boundary);
        modelInvocation.setVersion(0L);
        return modelInvocation;
    }

    private static ToolExecutionEntity execution(long index, String status) {
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId("execution-" + index);
        execution.setToolProcessItemId(new UUID(0L, index));
        execution.setAssistantConversationMessageId(ASSISTANT_ID);
        execution.setModelInvocationSequence(1L);
        execution.setToolCallId("call-" + index);
        execution.setToolName("tool-" + index);
        execution.setStatus(status);
        return execution;
    }

    private static ConversationMessage assistant(ToolExecutionEntity... executions) {
        ConversationMessage assistant = new ConversationMessage();
        assistant.setConversationMessageId(ASSISTANT_ID);
        assistant.setRole("assistant");
        assistant.setContent("");
        assistant.setThinkingProcess(
                java.util.Arrays.stream(executions)
                        .map(ReactCrashRecoveryIntegrationTest::terminalItem)
                        .map(ThinkingProcessItem.class::cast)
                        .toList());
        return assistant;
    }

    private static ToolThinkingProcessItem terminalItem(ToolExecutionEntity execution) {
        boolean succeeded = "succeeded".equals(execution.getStatus());
        Instant startedAt = Instant.parse("2026-08-04T00:00:00Z");
        return new ToolThinkingProcessItem(
                execution.getToolProcessItemId(),
                execution.getToolProcessItemId().getLeastSignificantBits(),
                succeeded ? ThinkingProcessStatus.SUCCEEDED : ThinkingProcessStatus.FAILED,
                startedAt,
                startedAt,
                startedAt.plusMillis(10L),
                10L,
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                execution.getToolName(),
                execution.getToolName(),
                execution.getToolExecutionId(),
                execution.getToolCallId(),
                execution.getModelInvocationSequence(),
                payload(Map.of()),
                succeeded ? payload(Map.of("ok", true)) : null,
                succeeded,
                succeeded ? null : "tool_error",
                succeeded ? null : "failed");
    }

    private static BoundedProcessPayload payload(Object value) {
        return new BoundedProcessPayload(
                value, null, false, 1L, PAYLOAD_SHA256, "application/json");
    }
}
