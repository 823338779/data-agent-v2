package com.datagent.agentv2.conversation.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 控制 reasoning 到期清理的分页大小、单轮上限和调度开关。 */
@ConfigurationProperties("data-agent.reasoning-retention")
public record ReasoningRetentionProperties(boolean enabled, int batchSize, int maxBatches) {

    private static final String ERROR_INVALID_BATCH_LIMITS =
            "Reasoning retention batch limits must be positive";

    public ReasoningRetentionProperties {
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException(ERROR_INVALID_BATCH_LIMITS);
        }
    }
}
