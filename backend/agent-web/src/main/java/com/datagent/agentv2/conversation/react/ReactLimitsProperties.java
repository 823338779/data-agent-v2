package com.datagent.agentv2.conversation.react;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "data-agent.react.limits")
public class ReactLimitsProperties {

    /** 单次 Agent 执行允许创建的最大模型调用数。 */
    private int maxModelInvocations = 12;

    /** 单次 Agent 执行允许归档的最大工具调用数。 */
    private int maxToolCalls = 32;

    public ReactLimitsProperties() {}

    public ReactLimitsProperties(int maxModelInvocations, int maxToolCalls) {
        this.maxModelInvocations = maxModelInvocations;
        this.maxToolCalls = maxToolCalls;
    }

    public int getMaxModelInvocations() {
        return maxModelInvocations;
    }

    public void setMaxModelInvocations(int maxModelInvocations) {
        this.maxModelInvocations = maxModelInvocations;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }
}
