package com.datagent.agentv2.conversation.tool.runtime;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 服务端 Tool 可见性策略。
 *
 * @param maximum 单次执行允许激活的最大 Tool 集合
 * @param initial 单次执行创建时立即可见的 Tool 集合，必须是 maximum 的子集
 */
@ConfigurationProperties("data-agent.tool")
public record ToolPolicyProperties(
        @DefaultValue({"system_timezone", "current_time", "skill_read", "web_search"})
                List<String> maximum,
        @DefaultValue({"system_timezone", "current_time", "skill_read"}) List<String> initial) {

    public ToolPolicyProperties {
        maximum = List.copyOf(maximum);
        initial = List.copyOf(initial);
    }
}
