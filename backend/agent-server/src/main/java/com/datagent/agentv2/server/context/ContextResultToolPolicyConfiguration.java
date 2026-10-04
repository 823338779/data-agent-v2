package com.datagent.agentv2.server.context;

import com.datagent.agentv2.conversation.tool.builtin.ContextResultReadTool;
import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 仅移除未启用策略的默认读取工具；显式 maximum/initial 从不被扩大。 */
@Configuration(proxyBeanMethods = false)
public class ContextResultToolPolicyConfiguration {
    private static final String STRATEGY = "data-agent.context.strategy";
    private static final String HIERARCHICAL = "hierarchical";

    @Bean
    static BeanPostProcessor contextResultToolPolicy(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof ToolPolicyProperties policy)
                        || HIERARCHICAL.equals(environment.getProperty(STRATEGY, HIERARCHICAL))) {
                    return bean;
                }
                return new ToolPolicyProperties(
                        policy.maximum().stream()
                                .filter(tool -> !ContextResultReadTool.TOOL_ID.equals(tool))
                                .toList(),
                        policy.initial().stream()
                                .filter(tool -> !ContextResultReadTool.TOOL_ID.equals(tool))
                                .toList());
            }
        };
    }
}
