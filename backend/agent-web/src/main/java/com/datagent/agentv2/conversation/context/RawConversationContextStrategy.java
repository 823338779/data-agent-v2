package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.react.ConversationContextLoader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 保持现有行为的原始上下文策略，用作默认实现和 LSM 插件的可回退实现。 */
@Component
@RequiredArgsConstructor
public class RawConversationContextStrategy implements ConversationContextStrategy {

    public static final String STRATEGY_ID = "raw";

    private final ConversationContextLoader loader;
    private final ContextModelMessageMapper messages;

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public PreparedConversationContext prepare(ConversationContextRequest request) {
        List<ContextMessage> contextMessages =
                messages.toContextMessages(
                        loader.load(
                                request.execution().executionId(),
                                request.execution().inputUpToMessageSequence()));
        return new PreparedConversationContext(
                STRATEGY_ID, contextMessages, request.budget().fixedInputTokens(), false, 0L);
    }
}
