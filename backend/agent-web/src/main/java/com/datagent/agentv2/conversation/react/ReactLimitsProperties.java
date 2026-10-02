package com.datagent.agentv2.conversation.react;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
@Component
@ConfigurationProperties(prefix = "data-agent.react.limits")
public class ReactLimitsProperties {

    /** 单次 Agent 执行允许创建的最大模型调用数。 */
    private int maxModelInvocations = 12;

    /** 单次 Agent 执行允许归档的最大工具调用数。 */
    private int maxToolCalls = 32;
}
