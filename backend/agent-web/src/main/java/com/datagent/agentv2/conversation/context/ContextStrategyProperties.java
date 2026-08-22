package com.datagent.agentv2.conversation.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 选择单次执行所使用的会话上下文策略。 */
@Component
@ConfigurationProperties("data-agent.context")
@Getter
@Setter
public class ContextStrategyProperties {

    private String strategy = RawConversationContextStrategy.STRATEGY_ID;
}
