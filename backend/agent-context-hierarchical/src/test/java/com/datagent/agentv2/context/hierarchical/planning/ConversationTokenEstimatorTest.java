package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ContextToolDefinition;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTokenEstimatorTest {

    private static final String CJK_TEXT = "你好，世界";
    private static final String LATIN_TEXT = "plain latin text";
    private static final String JSON_DOCUMENT = "{\"name\":\"天气\",\"enabled\":true}";
    private static final String SYSTEM_PROMPT = "system prompt";
    private static final String TOOL_NAME = "weather";
    private static final String TOOL_DESCRIPTION = "looks up weather";
    private static final String TOOL_CALL_ID = "call-1";
    private static final String TOOL_ARGUMENT_KEY = "city";
    private static final String TOOL_ARGUMENT = "Shanghai";
    private static final String TOOL_RESULT = "sunny";
    private static final String THINKING = "tool required";
    private static final String FINAL_ANSWER = "answer";
    private static final String EMPTY_CONTENT = "";
    private static final String TENANT_ID = "tenant";
    private static final String SESSION_ID = "session";
    private static final String EXECUTION_ID = "execution";
    private static final String OWNER_ID = "owner";
    private static final int FIXED_INPUT_TOKENS = 17;

    private final ConversationTokenEstimator estimator = new ConversationTokenEstimator();

    @Test
    void estimateIncludesFixedSystemToolsProtocolThinkingAndHistory() {
        ConversationContextRequest request = request(4_096);
        List<ContextMessage> history = contextMessages();

        int expected =
                request.budget().fixedInputTokens()
                        + estimator.estimateMessages(request.fixedSystemMessages())
                        + estimator.estimateTools(request.toolDefinitions())
                        + estimator.estimateMessages(history);

        assertThat(estimator.estimate(request, history)).isEqualTo(expected);
        assertThat(estimator.estimate(request, history))
                .isGreaterThan(request.budget().fixedInputTokens());
        assertThat(estimator.estimateTools(request.toolDefinitions())).isPositive();
    }

    @Test
    void everyCompleteInputComponentStrictlyIncreasesTheEstimate() {
        ConversationContextRequest emptyRequest = request(List.of(), List.of(), 4_096);
        ConversationContextRequest withSystem =
                request(List.of(ContextMessage.system(SYSTEM_PROMPT)), List.of(), 4_096);
        ConversationContextRequest withTools = request(List.of(), toolDefinitions(), 4_096);
        ContextMessage plainAssistant = ContextMessage.assistant(FINAL_ANSWER, null);
        ContextMessage thinkingAssistant = ContextMessage.assistant(FINAL_ANSWER, THINKING);
        ContextMessage toolCall =
                ContextMessage.assistantToolCalls(
                        List.of(
                                new ContextToolCall(
                                        TOOL_CALL_ID,
                                        TOOL_NAME,
                                        Map.of(TOOL_ARGUMENT_KEY, TOOL_ARGUMENT))));

        int base = estimator.estimate(emptyRequest, List.of());
        assertThat(estimator.estimate(withSystem, List.of())).isGreaterThan(base);
        assertThat(estimator.estimate(withTools, List.of())).isGreaterThan(base);
        assertThat(estimator.estimate(emptyRequest, List.of(ContextMessage.user(CJK_TEXT))))
                .isGreaterThan(base);
        assertThat(estimator.estimateMessages(List.of(ContextMessage.user(LATIN_TEXT))))
                .isGreaterThan(
                        estimator.estimateMessages(List.of(ContextMessage.user(EMPTY_CONTENT))));
        assertThat(estimator.estimateMessages(List.of(thinkingAssistant)))
                .isGreaterThan(estimator.estimateMessages(List.of(plainAssistant)));
        assertThat(estimator.estimateMessages(List.of(toolCall)))
                .isGreaterThan(
                        estimator.estimateMessages(
                                List.of(ContextMessage.assistantToolCalls(List.of()))));
        assertThat(
                        estimator.estimateMessages(
                                List.of(ContextMessage.tool(TOOL_RESULT, TOOL_CALL_ID, TOOL_NAME))))
                .isGreaterThan(
                        estimator.estimateMessages(
                                List.of(
                                        ContextMessage.tool(
                                                TOOL_RESULT, EMPTY_CONTENT, EMPTY_CONTENT))));
        assertThat(estimator.estimateSummary(JSON_DOCUMENT))
                .isGreaterThan(JSON_DOCUMENT.codePointCount(0, JSON_DOCUMENT.length()));
    }

    @Test
    void safetyReserveDoesNotBecomeEstimatedBusinessInput() {
        int smallReserve = estimator.estimate(request(1), contextMessages());
        int largeReserve = estimator.estimate(request(8_000), contextMessages());

        assertThat(largeReserve).isEqualTo(smallReserve);
    }

    private static List<ContextMessage> contextMessages() {
        return List.of(
                ContextMessage.assistantToolCalls(
                        List.of(
                                new ContextToolCall(
                                        TOOL_CALL_ID,
                                        TOOL_NAME,
                                        Map.of(TOOL_ARGUMENT_KEY, TOOL_ARGUMENT)))),
                ContextMessage.tool(TOOL_RESULT, TOOL_CALL_ID, TOOL_NAME),
                ContextMessage.assistant(FINAL_ANSWER, THINKING));
    }

    private static List<ContextToolDefinition> toolDefinitions() {
        return List.of(
                new ContextToolDefinition(
                        TOOL_NAME, TOOL_DESCRIPTION, Map.of(TOOL_ARGUMENT_KEY, TOOL_ARGUMENT)));
    }

    private static ConversationContextRequest request(int safetyReserveTokens) {
        return request(
                List.of(ContextMessage.system(SYSTEM_PROMPT)),
                toolDefinitions(),
                safetyReserveTokens);
    }

    private static ConversationContextRequest request(
            List<ContextMessage> fixedSystemMessages,
            List<ContextToolDefinition> toolDefinitions,
            int safetyReserveTokens) {
        return new ConversationContextRequest(
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 3L),
                new ContextBudget(65_536, 4_096, safetyReserveTokens, FIXED_INPUT_TOKENS),
                fixedSystemMessages,
                toolDefinitions);
    }
}
