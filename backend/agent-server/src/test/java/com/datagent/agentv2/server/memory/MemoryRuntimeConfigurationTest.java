package com.datagent.agentv2.server.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.memory.MemoryContentPolicy;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.context.MemoryContextRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MemoryRuntimeConfigurationTest {
    @Test
    void defaultToolPolicyIncludesMemoryAndExplicitDisableRemainsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ToolConfiguration.class)
                .run(
                        context -> {
                            var policy = context.getBean(ToolPolicyProperties.class);
                            assertThat(policy.initial()).contains("memory");
                            assertThat(policy.maximum()).contains("memory");
                        });
        new ApplicationContextRunner()
                .withUserConfiguration(ToolConfiguration.class)
                .withPropertyValues(
                        "data-agent.tool.initial=current_time",
                        "data-agent.tool.maximum=current_time")
                .run(
                        context -> {
                            var policy = context.getBean(ToolPolicyProperties.class);
                            assertThat(policy.initial()).containsExactly("current_time");
                            assertThat(policy.maximum()).containsExactly("current_time");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ToolPolicyProperties.class)
    static class ToolConfiguration {}

    @Test
    void registersRulesWithBoundProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryRuntimeConfiguration.class)
                .withPropertyValues("data-agent.memory.person-profile-limit=123")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(MemoryProperties.class)
                                    .hasSingleBean(MemoryContentPolicy.class)
                                    .hasSingleBean(MemoryContextRenderer.class);
                            assertThat(
                                            context.getBean(MemoryContentPolicy.class)
                                                    .limit(MemoryKind.PERSON_PROFILE))
                                    .isEqualTo(123);
                        });
    }
}
