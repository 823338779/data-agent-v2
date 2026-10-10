package com.datagent.agentv2.server.dbt;

import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.dbt.tool.*;
import java.util.ArrayList;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** 只扩展最大工具集合，初始集合保持不变；与 Git 的 allowance 可以顺序组合。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.dbt", name = "enabled", havingValue = "true")
public class DbtRuntimeConfiguration {
    @Bean
    static BeanPostProcessor dbtToolAllowance(Environment environment) {
        var tools = new ArrayList<>(DbtAnalysisTool.TOOL_IDS);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof ToolPolicyProperties policy)) return bean;
                if (policy.initial().stream().anyMatch(tools::contains))
                    throw new IllegalStateException("dbt 工具必须通过 Skill 激活");
                var maximum = new ArrayList<>(policy.maximum());
                for (var tool : tools) if (!maximum.contains(tool)) maximum.add(tool);
                return new ToolPolicyProperties(maximum, policy.initial());
            }
        };
    }
}
