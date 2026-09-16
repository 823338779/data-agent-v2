package com.datagent.agentv2.server.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.memory.MemoryContentPolicy;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.config.MemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryRuntimeConfigurationTest {
    @Test
    void registersRulesWithBoundProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryRuntimeConfiguration.class)
                .withPropertyValues("data-agent.memory.person-profile-limit=123")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(MemoryProperties.class)
                                    .hasSingleBean(MemoryContentPolicy.class);
                            assertThat(
                                            context.getBean(MemoryContentPolicy.class)
                                                    .limit(MemoryKind.PERSON_PROFILE))
                                    .isEqualTo(123);
                        });
    }
}
