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

    private static final String PROPERTY_MAX_RUNNING_RUNS = "maxRunningRunsPerInstance";
    private static final String PROPERTY_QUEUE_SCAN_INTERVAL = "queueScanInterval";
    private static final String PROPERTY_CONTROL_POLL_INTERVAL = "controlPollInterval";
    private static final String PROPERTY_RUN_EXECUTION_BUDGET = "runExecutionBudget";
    private static final String PROPERTY_LEASE_DURATION = "leaseDuration";
    private static final String PROPERTY_HEARTBEAT_INTERVAL = "heartbeatInterval";
    private static final String ERROR_MUST_BE_POSITIVE = " must be positive";

    public RunRuntimeProperties {
        if (maxRunningRunsPerInstance <= 0) {
            throw new IllegalArgumentException(PROPERTY_MAX_RUNNING_RUNS + ERROR_MUST_BE_POSITIVE);
        }
        requirePositive(queueScanInterval, PROPERTY_QUEUE_SCAN_INTERVAL);
        requirePositive(controlPollInterval, PROPERTY_CONTROL_POLL_INTERVAL);
        requirePositive(runExecutionBudget, PROPERTY_RUN_EXECUTION_BUDGET);
        requirePositive(leaseDuration, PROPERTY_LEASE_DURATION);
        requirePositive(heartbeatInterval, PROPERTY_HEARTBEAT_INTERVAL);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + ERROR_MUST_BE_POSITIVE);
        }
    }
}
