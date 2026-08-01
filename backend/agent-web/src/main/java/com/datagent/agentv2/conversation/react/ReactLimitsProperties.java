package com.datagent.agentv2.conversation.react;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "data-agent.react.limits")
public class ReactLimitsProperties {

    /** 单个 Run 允许创建的最大 Model Turn 数。 */
    private int maxModelTurns = 12;

    /** 单个 Run 允许归档的最大工具调用数。 */
    private int maxToolCalls = 32;

    /** reasoning 检查点在 stream 和 Model Turn 中的保留时长。 */
    private Duration reasoningRetention = Duration.ofHours(24);

    public ReactLimitsProperties() {}

    public ReactLimitsProperties(int maxModelTurns, int maxToolCalls) {
        this.maxModelTurns = maxModelTurns;
        this.maxToolCalls = maxToolCalls;
    }

    public int getMaxModelTurns() {
        return maxModelTurns;
    }

    public void setMaxModelTurns(int maxModelTurns) {
        this.maxModelTurns = maxModelTurns;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public Duration getReasoningRetention() {
        return reasoningRetention;
    }

    public void setReasoningRetention(Duration reasoningRetention) {
        this.reasoningRetention = reasoningRetention;
    }
}
