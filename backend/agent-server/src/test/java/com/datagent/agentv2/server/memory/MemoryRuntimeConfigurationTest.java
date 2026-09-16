package com.datagent.agentv2.server.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.memory.MemoryContentPolicy;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryReviewScheduler;
import com.datagent.agentv2.memory.MemoryService;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.context.MemoryContextRenderer;
import com.datagent.agentv2.memory.observability.MemoryMetrics;
import com.datagent.agentv2.memory.review.*;
import com.datagent.agentv2.memory.tool.MemoryToolDispatcher;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

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
                .withUserConfiguration(
                        MemoryRuntimeConfiguration.class, MemoryReviewScheduler.class)
                .withBean(MemoryService.class, () -> mock(MemoryService.class))
                .withBean(MemoryReviewCoordinator.class, () -> mock(MemoryReviewCoordinator.class))
                .withBean(MemoryReviewHistoryPort.class, () -> mock(MemoryReviewHistoryPort.class))
                .withBean(MemoryReviewModelPort.class, () -> mock(MemoryReviewModelPort.class))
                .withBean(MemoryToolDispatcher.class, () -> mock(MemoryToolDispatcher.class))
                .withBean(MemoryOperationService.class, () -> mock(MemoryOperationService.class))
                .withBean(
                        com.datagent.agentv2.persistence.mapper.memory.MemoryOperationMapper.class,
                        () ->
                                mock(
                                        com.datagent.agentv2.persistence.mapper.memory
                                                .MemoryOperationMapper.class))
                .withBean(
                        MemoryMetrics.class,
                        () ->
                                new MemoryMetrics(
                                        new io.micrometer.core.instrument.simple
                                                .SimpleMeterRegistry()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
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
                            var pool =
                                    context.getBean(
                                            "memoryReviewExecutor", ThreadPoolExecutor.class);
                            assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
                            assertThat(pool.getQueue().remainingCapacity()).isZero();
                            var memory = context.getBean(MemoryService.class);
                            verifyNoInteractions(memory);
                            context.getBean(ApplicationRunner.class)
                                    .run(new DefaultApplicationArguments());
                            verify(memory).initialize();
                            context.getBean(MemoryReviewScheduler.class).scan();
                            verifyNoInteractions(context.getBean(MemoryReviewCoordinator.class));
                        });
    }
}
