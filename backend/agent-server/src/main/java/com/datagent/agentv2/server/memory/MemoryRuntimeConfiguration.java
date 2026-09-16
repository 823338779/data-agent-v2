package com.datagent.agentv2.server.memory;

import com.datagent.agentv2.memory.MemoryContentPolicy;
import com.datagent.agentv2.memory.config.MemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 记忆能力的最小运行时装配；业务规则留在 agent-memory，后台初始化与调度在后续任务接入。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryRuntimeConfiguration {
    @Bean
    MemoryContentPolicy memoryContentPolicy(MemoryProperties properties) {
        return new MemoryContentPolicy(properties);
    }
}
