package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 在现有模型消息与供应商无关的上下文契约之间执行无损协议映射。 */
@Component
@RequiredArgsConstructor
public class ContextModelMessageMapper {

    private final ContextModelMessageStructMapper mappings;

    private final ObjectMapper json;

    public List<ContextMessage> toContextMessages(List<ModelInputMessage> messages) {
        return mappings.toContextMessages(messages, json);
    }

    /** 将策略输出无损恢复为现有模型网关使用的协议消息。 */
    public List<ModelInputMessage> toModelMessages(List<ContextMessage> messages) {
        return mappings.toModelMessages(messages, json);
    }

    public List<ContextToolDefinition> toContextToolDefinitions(List<ModelToolDefinition> tools) {
        return mappings.toContextToolDefinitions(tools);
    }
}
