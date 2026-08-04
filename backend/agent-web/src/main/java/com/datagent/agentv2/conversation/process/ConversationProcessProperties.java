package com.datagent.agentv2.conversation.process;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 会话 ProcessItem 单节点持久化容量配置；不限制外层有序列表总大小。 */
@ConfigurationProperties("data-agent.conversation.process")
public record ConversationProcessProperties(
        /** 单个 THINKING 节点正文允许持久化的最大 UTF-8 字节数。 */
        @DefaultValue("16384") int maxThinkingBytes,
        /** 单个 TOOL 节点入参允许持久化的最大 UTF-8 字节数。 */
        @DefaultValue("32768") int maxToolInputBytes,
        /** 单个 TOOL 节点出参允许持久化的最大 UTF-8 字节数。 */
        @DefaultValue("65536") int maxToolOutputBytes) {

    /** 拒绝会使节点边界失效的非正配置。 */
    public ConversationProcessProperties {
        if (maxThinkingBytes <= 0 || maxToolInputBytes <= 0 || maxToolOutputBytes <= 0) {
            throw new IllegalArgumentException("conversation process byte limits must be positive");
        }
    }
}
