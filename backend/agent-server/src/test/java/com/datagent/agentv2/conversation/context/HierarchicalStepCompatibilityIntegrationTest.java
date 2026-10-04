package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.process.ContextPayloadArchive;
import com.datagent.agentv2.conversation.tool.builtin.ContextResultReadTool;
import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.server.context.ContextResultRuntimeConfiguration;
import com.datagent.agentv2.server.context.ContextResultToolPolicyConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 验证策略切换的实际 Spring 条件及显式工具权限边界。 */
class HierarchicalStepCompatibilityIntegrationTest {
    private static final String STRATEGY = "data-agent.context.strategy=";
    private static final String CLOCK = "current_time";

    @Test
    void rawAndLsmDoNotRegisterArchiveOrReadTool() {
        for (String strategy : List.of("raw", "lsm")) {
            new ApplicationContextRunner()
                    .withPropertyValues(STRATEGY + strategy)
                    .withUserConfiguration(
                            ContextResultRuntimeConfiguration.class,
                            ContextResultToolPolicyConfiguration.class)
                    .withBean(
                            ToolPolicyProperties.class,
                            () ->
                                    new ToolPolicyProperties(
                                            List.of(CLOCK, ContextResultReadTool.TOOL_ID),
                                            List.of(CLOCK, ContextResultReadTool.TOOL_ID)))
                    .run(
                            context -> {
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(ContextResultReadTool.class)
                                        .doesNotHaveBean(ContextPayloadArchive.class);
                                assertThat(context.getBean(ToolPolicyProperties.class).maximum())
                                        .containsExactly(CLOCK);
                            });
        }
    }

    @Test
    void hierarchicalNeverExpandsExplicitToolAllowance() {
        new ApplicationContextRunner()
                .withPropertyValues(STRATEGY + "hierarchical")
                .withUserConfiguration(ContextResultToolPolicyConfiguration.class)
                .withBean(
                        ToolPolicyProperties.class,
                        () -> new ToolPolicyProperties(List.of(CLOCK), List.of(CLOCK)))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(ToolPolicyProperties.class).maximum())
                                    .containsExactly(CLOCK);
                            assertThat(context.getBean(ToolPolicyProperties.class).initial())
                                    .containsExactly(CLOCK);
                        });
    }
}
