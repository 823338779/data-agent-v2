package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.react.ConversationContextLoader;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationContextStrategyResolverTest {

    private static final String STRATEGY_RAW = "raw";
    private static final String STRATEGY_LSM = "lsm";

    @Test
    void selectsExactlyConfiguredStrategy() {
        ConversationContextStrategy raw = strategy(STRATEGY_RAW);
        ConversationContextStrategy lsm = strategy(STRATEGY_LSM);

        ConversationContextStrategyResolver resolver =
                new ConversationContextStrategyResolver(
                        List.of(raw, lsm), properties(STRATEGY_LSM));

        assertThat(resolver.selected()).isSameAs(lsm);
    }

    @Test
    void rejectsUnknownAndDuplicateStrategyIds() {
        assertThatThrownBy(
                        () ->
                                new ConversationContextStrategyResolver(
                                        List.of(strategy(STRATEGY_RAW)), properties(STRATEGY_LSM)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(STRATEGY_LSM);

        assertThatThrownBy(
                        () ->
                                new ConversationContextStrategyResolver(
                                        List.of(strategy(STRATEGY_RAW), strategy(STRATEGY_RAW)),
                                        properties(STRATEGY_RAW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rawStrategyPreservesExistingToolProtocolOrder() {
        ConversationContextLoader loader = mock(ConversationContextLoader.class);
        JsonMapper json = JsonMapper.builder().build();
        ContextModelMessageMapper messages = new ContextModelMessageMapper(json);
        ModelToolCall call = new ModelToolCall("call-1", "lookup", json.readTree("{\"q\":\"x\"}"));
        when(loader.load("execution-1", 4L))
                .thenReturn(
                        List.of(
                                ModelInputMessage.plain("user", "question"),
                                ModelInputMessage.assistantToolCalls(List.of(call)),
                                ModelInputMessage.tool("result", "call-1", "lookup")));
        RawConversationContextStrategy strategy =
                new RawConversationContextStrategy(loader, messages);

        PreparedConversationContext prepared = strategy.prepare(request());

        assertThat(prepared.strategyId()).isEqualTo(STRATEGY_RAW);
        assertThat(prepared.compacted()).isFalse();
        assertThat(prepared.messages())
                .extracting(ContextMessage::role)
                .containsExactly(
                        ContextMessage.Role.USER,
                        ContextMessage.Role.ASSISTANT,
                        ContextMessage.Role.TOOL);
        assertThat(prepared.messages().get(1).toolCalls())
                .containsExactly(new ContextToolCall("call-1", "lookup", Map.of("q", "x")));
    }

    private static ConversationContextRequest request() {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant-1", "session-1", "execution-1", "owner-1", 1L, 4L),
                new ContextBudget(65_536, 4_096, 4_096, 0),
                List.of(),
                List.of());
    }

    private static ConversationContextStrategy strategy(String id) {
        ConversationContextStrategy strategy = mock(ConversationContextStrategy.class);
        when(strategy.strategyId()).thenReturn(id);
        return strategy;
    }

    private static ContextStrategyProperties properties(String strategy) {
        ContextStrategyProperties properties = new ContextStrategyProperties();
        properties.setStrategy(strategy);
        return properties;
    }
}
