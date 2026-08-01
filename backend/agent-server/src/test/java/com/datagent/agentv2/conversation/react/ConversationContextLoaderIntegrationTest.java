package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentToolExecutionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationContextLoaderIntegrationTest {

    private static final String RUN_ID = "run_context";
    private static final String TOOL_CALLS =
            """
            {"version":1,"calls":[{"id":"call-1","name":"catalog_query","arguments":{"limit":10}}]}
            """;

    private final AgentMessageService messages = mock(AgentMessageService.class);
    private final AgentToolExecutionService executions = mock(AgentToolExecutionService.class);
    private final ConversationContextLoader loader =
            new ConversationContextLoader(messages, executions, JsonMapper.builder().build());

    @Test
    void rebuildsCompletePublicAndInternalMessagesInSequenceAtBoundary() {
        when(messages.findContextByRunIdUpTo(RUN_ID, 5))
                .thenReturn(
                        List.of(
                                message(1, "system", "system", null, null, null, null),
                                message(2, "user", "question", null, null, null, null),
                                message(3, "assistant", "", TOOL_CALLS, null, null, null),
                                message(
                                        4,
                                        "tool",
                                        "{\"status\":\"failed\",\"content\":{}}",
                                        null,
                                        "execution-1",
                                        "call-1",
                                        "catalog_query"),
                                message(5, "assistant", "final", null, null, null, null)));
        when(executions.findByRunId(RUN_ID)).thenReturn(List.of(execution()));

        List<ModelInputMessage> context = loader.load(RUN_ID, 5);

        assertThat(context)
                .extracting(ModelInputMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "assistant");
        assertThat(context.get(2).toolCalls())
                .containsExactly(
                        new ToolCallDocument(
                                "call-1",
                                "catalog_query",
                                JsonMapper.builder().build().readTree("{\"limit\":10}")));
        assertThat(context.get(3).toolCallId()).isEqualTo("call-1");
        assertThat(context.get(3).toolName()).isEqualTo("catalog_query");
    }

    @Test
    void truncatesAtInputBoundaryAndNeverReadsPartialStreamState() {
        when(messages.findContextByRunIdUpTo(RUN_ID, 2))
                .thenReturn(
                        List.of(
                                message(1, "system", "system", null, null, null, null),
                                message(2, "user", "question", null, null, null, null)));
        when(executions.findByRunId(RUN_ID)).thenReturn(List.of());

        assertThat(loader.load(RUN_ID, 2))
                .extracting(ModelInputMessage::content)
                .containsExactly("system", "question");
    }

    @Test
    void rejectsObservationWhoseExecutionProviderIdOrNameDoesNotMatchAssistantCall() {
        AgentMessageEntity assistant = message(1, "assistant", "", TOOL_CALLS, null, null, null);
        AgentMessageEntity tool =
                message(2, "tool", "{}", null, "execution-1", "wrong-call", "catalog_query");
        when(messages.findContextByRunIdUpTo(RUN_ID, 2)).thenReturn(List.of(assistant, tool));
        when(executions.findByRunId(RUN_ID)).thenReturn(List.of(execution()));

        assertThatThrownBy(() -> loader.load(RUN_ID, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tool observation does not match its assistant tool call");
    }

    private static AgentMessageEntity message(
            long sequence,
            String role,
            String content,
            String toolCalls,
            String executionId,
            String toolCallId,
            String toolName) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setMessageId("message-" + sequence);
        message.setRunId(RUN_ID);
        message.setSequence(sequence);
        message.setRole(role);
        message.setContent(content);
        message.setToolCalls(toolCalls);
        message.setToolExecutionId(executionId);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        return message;
    }

    private static AgentToolExecutionEntity execution() {
        AgentToolExecutionEntity execution = new AgentToolExecutionEntity();
        execution.setToolExecutionId("execution-1");
        execution.setAssistantMessageId("message-3");
        execution.setToolCallId("call-1");
        execution.setToolName("catalog_query");
        return execution;
    }
}
