package com.datagent.agentv2.conversation.runtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("data-agent.runtime")
public record RunRuntimeProperties(
        @DefaultValue("256") int maxRunningRunsPerInstance,
        @DefaultValue("1s") Duration queueScanInterval,
        @DefaultValue("1s") Duration controlPollInterval,
        @DefaultValue("10m") Duration runExecutionBudget,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("true") boolean schedulingEnabled) {

    public RunRuntimeProperties {
        if (maxRunningRunsPerInstance <= 0) {
            throw new IllegalArgumentException("maxRunningRunsPerInstance must be positive");
        }
        requirePositive(queueScanInterval, "queueScanInterval");
        requirePositive(controlPollInterval, "controlPollInterval");
        requirePositive(runExecutionBudget, "runExecutionBudget");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatInterval, "heartbeatInterval");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
