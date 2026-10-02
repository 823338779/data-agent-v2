package com.datagent.agentv2.platform.time;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供通用应用时钟，使 Git 等能力的时间边界不依赖可选业务模块。 */
@Configuration(proxyBeanMethods = false)
public class SystemClockConfiguration {

    /** 使用 UTC 绝对时间；允许测试或运行时装配提供专用时钟。 */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
