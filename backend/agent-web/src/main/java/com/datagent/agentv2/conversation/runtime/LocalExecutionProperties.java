package com.datagent.agentv2.conversation.runtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("data-agent.execution")
/**
 * 单实例执行的时间边界配置。
 *
 * @param deadline 从执行创建开始计算的最大存活时长；必须为正数，默认三十五分钟以容纳同步 dbt 查询
 */
public record LocalExecutionProperties(@DefaultValue("35m") Duration deadline) {

    public LocalExecutionProperties {
        if (deadline == null || deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
    }
}
