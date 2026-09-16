package com.datagent.agentv2.context.lsm.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextInputEstimator;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ContextToolDefinition;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationTokenEstimatorTest {

    private static final String CJK_TEXT = "你好，世界";
    private static final String LATIN_TEXT = "plain latin text";
    private static final String JSON_DOCUMENT = "{\"name\":\"天气\",\"enabled\":true}";
    private static final String SYSTEM_PROMPT = "system prompt";
    private static final String TOOL_NAME = "weather";
    private static final String TOOL_DESCRIPTION = "looks up weather";
    private static final String TOOL_CALL_ID = "call-1";
    private static final String TOOL_ARGUMENT = "Shanghai";
    private static final String TOOL_RESULT = "sunny";
    private static final String THINKING = "tool required";
    private static final String APPENDED_CONTENT = "x";
    private static final String FINAL_ANSWER = "answer";
    private static final String CHARACTER_VALUE = "x";
    private static final String UUID_VALUE = "0a1b2c3d-4e5f-6789-8abc-def012345678";
    private static final String JSON_VALUE_KEY = "value";
    private static final String TENANT_ID = "tenant";
    private static final String SESSION_ID = "session";
    private static final String EXECUTION_ID = "execution";
    private static final String OWNER_ID = "owner";
    private static final long RANDOM_SEED = 73L;
    private static final int GENERATED_MESSAGE_COUNT = 10_000;
    private static final int FIXED_INPUT_TOKENS = 17;

    private final ConversationTokenEstimator estimator = new ConversationTokenEstimator();

    @Test
    void pureEstimatorPreservesStrategyAndExistingTextUnit() {
        ContextInputEstimator pure = new ConversationTokenEstimator();
        assertThat(pure.strategyId()).isEqualTo("lsm");
        var base =
                new ConversationContextRequest(
                        new ContextExecutionRef("tenant", "session", "execution", 1),
                        new ContextBudget(1000, 1, 0, 0),
                        List.of(),
                        List.of());
        int difference =
                pure.estimateCompleteInput(base, List.of(ContextMessage.user("中")))
                        - pure.estimateCompleteInput(base, List.of(ContextMessage.user("")));
        assertThat(difference).isEqualTo(1);
    }

    @Test
    void estimatesCjkLatinAndJsonConservatively() {
        int emptyUser = estimator.estimateMessages(List.of(ContextMessage.user("")));

        assertThat(estimator.estimateMessages(List.of(ContextMessage.user(CJK_TEXT))))
                .isGreaterThanOrEqualTo(emptyUser + CJK_TEXT.codePointCount(0, CJK_TEXT.length()));
        assertThat(estimator.estimateMessages(List.of(ContextMessage.user(LATIN_TEXT))))
                .isGreaterThanOrEqualTo(
                        emptyUser + LATIN_TEXT.codePointCount(0, LATIN_TEXT.length()));
        assertThat(estimator.estimateSummaryDocument(JSON_DOCUMENT))
                .isGreaterThan(JSON_DOCUMENT.codePointCount(0, JSON_DOCUMENT.length()));
    }

    @Test
    void includesFixedMessagesToolsAndProtocolBudgetInCompleteInput() {
        ConversationContextRequest request = request();
        List<ContextMessage> history =
                List.of(
                        ContextMessage.assistantToolCalls(
                                List.of(
                                        new ContextToolCall(
                                                TOOL_CALL_ID,
                                                TOOL_NAME,
                                                Map.of(TOOL_NAME, TOOL_ARGUMENT)))),
                        ContextMessage.tool(TOOL_RESULT, TOOL_CALL_ID, TOOL_NAME),
                        ContextMessage.assistant(FINAL_ANSWER, THINKING));

        int expected =
                request.budget().fixedInputTokens()
                        + estimator.estimateMessages(request.fixedSystemMessages())
                        + estimator.estimateTools(request.toolDefinitions())
                        + estimator.estimateMessages(history);

        assertThat(estimator.estimateCompleteInput(request, history)).isEqualTo(expected);
    }

    @Test
    void remainsMonotonicForTenThousandFixedSeedMixedMessages() {
        Random random = new Random(RANDOM_SEED);

        // 固定种子覆盖普通文本、中文、thinking 与工具协议，保证估算结果可重复比较。
        for (int index = 0; index < GENERATED_MESSAGE_COUNT; index++) {
            ContextMessage message = randomMessage(random, index);
            ContextMessage appended =
                    new ContextMessage(
                            message.role(),
                            message.content() + APPENDED_CONTENT,
                            message.thinking(),
                            message.toolCalls(),
                            message.toolCallId(),
                            message.toolName());

            assertThat(estimator.estimateMessages(List.of(appended)))
                    .isGreaterThanOrEqualTo(estimator.estimateMessages(List.of(message)));
        }
    }

    @Test
    void estimatesJsonStringLikeCharacterEnumAndUuidWithStringBoundaries() {
        UUID uuid = UUID.fromString(UUID_VALUE);

        // JSON 序列化会把这些类型写成字符串，估算必须保留对应引号边界。
        assertThat(estimateToolParameter(Character.valueOf(CHARACTER_VALUE.charAt(0))))
                .isEqualTo(estimateToolParameter(CHARACTER_VALUE));
        assertThat(estimateToolParameter(ContextMessage.Role.USER))
                .isEqualTo(estimateToolParameter(ContextMessage.Role.USER.name()));
        assertThat(estimateToolParameter(uuid)).isEqualTo(estimateToolParameter(uuid.toString()));
    }

    private static ContextMessage randomMessage(Random random, int index) {
        String content = random.nextBoolean() ? CJK_TEXT : LATIN_TEXT;
        if (random.nextBoolean()) {
            return ContextMessage.assistant(content + index, THINKING);
        }
        if (random.nextBoolean()) {
            return ContextMessage.tool(content, TOOL_CALL_ID + index, TOOL_NAME);
        }
        return ContextMessage.user(content + index);
    }

    private int estimateToolParameter(Object value) {
        return estimator.estimateTools(
                List.of(
                        new ContextToolDefinition(
                                TOOL_NAME, TOOL_DESCRIPTION, Map.of(JSON_VALUE_KEY, value))));
    }

    private static ConversationContextRequest request() {
        return new ConversationContextRequest(
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, 3L),
                new ContextBudget(65_536, 4_096, 4_096, FIXED_INPUT_TOKENS),
                List.of(ContextMessage.system(SYSTEM_PROMPT)),
                List.of(
                        new ContextToolDefinition(
                                TOOL_NAME, TOOL_DESCRIPTION, Map.of(TOOL_NAME, TOOL_ARGUMENT))));
    }
}
