package com.datagent.agentv2.conversation.react;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ModelThinkingProcessTest {

    private static final String TENANT_ID = "tenant_model_process";
    private static final String EXECUTION_ID = "execution_model_process";
    private static final String OWNER_ID = "owner_model_process";
    private static final String CONVERSATION_SESSION_ID = "session_model_process";
    private static final String PROVIDER = "codex";
    private static final String MODEL = "gpt-5";
    private static final String FIRST_DELTA = "分析";
    private static final String SECOND_DELTA = "完成";
    private static final String ANSWER_DELTA = "answer";
    private static final UUID FIRST_ITEM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SECOND_ITEM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    private final AgentExecutionPort.ClaimedAgentExecution run =
            new AgentExecutionPort.ClaimedAgentExecution(TENANT_ID, EXECUTION_ID, OWNER_ID, 1L);
    private final ConversationProcessCoordinator processes =
            mock(ConversationProcessCoordinator.class);
    private final ExecutionStreamCheckpointCoordinator stream =
            mock(ExecutionStreamCheckpointCoordinator.class);
    private ModelInvocationCoordinator coordinator;

    @BeforeEach
    void createCoordinator() {
        when(processes.startThinking(
                        eq(run), eq(1L), eq(PROVIDER), eq(MODEL), eq(FIRST_DELTA), any()))
                .thenReturn(FIRST_ITEM_ID);
        when(processes.startThinking(
                        eq(run), eq(2L), eq(PROVIDER), eq(MODEL), eq(FIRST_DELTA), any()))
                .thenReturn(SECOND_ITEM_ID);
        when(stream.appendAnswer(eq(run), eq(1L), eq(ANSWER_DELTA), any()))
                .thenAnswer(
                        invocation ->
                                new ExecutionStreamCheckpointCoordinator.AnswerDelta(
                                        TENANT_ID,
                                        CONVERSATION_SESSION_ID,
                                        EXECUTION_ID,
                                        1L,
                                        1L,
                                        1L,
                                        ANSWER_DELTA,
                                        invocation.getArgument(3, Instant.class)));

        coordinator =
                new ModelInvocationCoordinator(
                        mock(ModelInvocationService.class),
                        mock(ToolExecutionService.class),
                        mock(ConversationMessageService.class),
                        mock(ConversationSessionService.class),
                        mock(AgentExecutionService.class),
                        mock(AgentExecutionStreamCheckpointService.class),
                        stream,
                        processes,
                        mock(ApplicationEventPublisher.class),
                        mock(AgentExecutionEventService.class),
                        mock(AgentExecutionAttemptService.class),
                        mock(IdGenerator.class));
    }

    @Test
    void firstThinkingDeltaStartsOneItemLaterDeltasAppendAndAnswerFinishesItOnce() {
        ModelInvocationCoordinator.ModelInvocationBoundary firstBoundary =
                new ModelInvocationCoordinator.ModelInvocationBoundary(1L, 1L);

        coordinator.checkpoint(
                run, firstBoundary, ModelOutputDelta.thinking(FIRST_DELTA, PROVIDER, MODEL));
        coordinator.checkpoint(
                run, firstBoundary, ModelOutputDelta.thinking(SECOND_DELTA, PROVIDER, MODEL));
        coordinator.checkpoint(
                run, firstBoundary, ModelOutputDelta.answer(ANSWER_DELTA, PROVIDER, MODEL));

        verify(processes)
                .startThinking(eq(run), eq(1L), eq(PROVIDER), eq(MODEL), eq(FIRST_DELTA), any());
        verify(processes).appendThinking(eq(run), eq(FIRST_ITEM_ID), eq(SECOND_DELTA), any());
        verify(processes, times(1))
                .finishThinking(
                        eq(run), eq(FIRST_ITEM_ID), eq(ThinkingProcessStatus.SUCCEEDED), any());
        verify(stream).appendAnswer(eq(run), eq(1L), eq(ANSWER_DELTA), any());

        // 新模型调用必须创建新 UUID，不能复用上一轮已经完成的 thinking 节点。
        ModelInvocationCoordinator.ModelInvocationBoundary secondBoundary =
                new ModelInvocationCoordinator.ModelInvocationBoundary(2L, 1L);
        coordinator.checkpoint(
                run, secondBoundary, ModelOutputDelta.thinking(FIRST_DELTA, PROVIDER, MODEL));
        verify(processes)
                .startThinking(eq(run), eq(2L), eq(PROVIDER), eq(MODEL), eq(FIRST_DELTA), any());
    }
}
