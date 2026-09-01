package com.datagent.agentv2.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** 数据分析 Workflow 的服务级硬边界，运行中不能由模型修改。 */
@ConfigurationProperties("data-agent.workflow")
public record DataAnalysisWorkflowProperties(
        @DefaultValue("5") int maxQueryTasks,
        @DefaultValue("3") int maxQueryPlanAttempts,
        @DefaultValue("3") int maxSqlAttemptsPerTask,
        @DefaultValue("1") int maxTransientQueryRetries,
        @DefaultValue("64KB") DataSize maxGeneratedSqlBytes,
        @DefaultValue("30") int maxSemanticResources,
        @DefaultValue("512KB") DataSize maxAnalysisInputBytes,
        @DefaultValue("120s") Duration hardTimeout) {

    public DataAnalysisWorkflowProperties {
        requireRange(maxQueryTasks, 1, 20, "max-query-tasks");
        requireRange(maxQueryPlanAttempts, 1, 10, "max-query-plan-attempts");
        requireRange(maxSqlAttemptsPerTask, 1, 10, "max-sql-attempts-per-task");
        requireRange(maxTransientQueryRetries, 0, 5, "max-transient-query-retries");
        requireSize(maxGeneratedSqlBytes, DataSize.ofMegabytes(1), "max-generated-sql-bytes");
        requireRange(maxSemanticResources, 1, 200, "max-semantic-resources");
        requireSize(maxAnalysisInputBytes, DataSize.ofMegabytes(10), "max-analysis-input-bytes");
        if (hardTimeout == null
                || hardTimeout.isZero()
                || hardTimeout.isNegative()
                || hardTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("hard-timeout 必须在 (0, 30m] 范围内");
        }
    }

    public static DataAnalysisWorkflowProperties defaults() {
        return new DataAnalysisWorkflowProperties(
                5,
                3,
                3,
                1,
                DataSize.ofKilobytes(64),
                30,
                DataSize.ofKilobytes(512),
                Duration.ofSeconds(120));
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + ".." + max + " 范围内");
        }
    }

    private static void requireSize(DataSize value, DataSize max, String name) {
        if (value == null || value.toBytes() <= 0 || value.toBytes() > max.toBytes()) {
            throw new IllegalArgumentException(name + " 超出服务端硬边界");
        }
    }
}
