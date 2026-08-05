package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 在现有模型消息与供应商无关的上下文契约之间执行无损协议映射。 */
@Component
public class ContextModelMessageMapper {

    private final ObjectMapper json;

    public ContextModelMessageMapper(ObjectMapper json) {
        this.json = json;
    }

    public List<ContextMessage> toContextMessages(List<ModelInputMessage> messages) {
        return messages.stream().map(this::toContextMessage).toList();
    }

    private ContextMessage toContextMessage(ModelInputMessage message) {
        List<ContextToolCall> toolCalls =
                message.toolCalls().stream().map(this::toContextToolCall).toList();
        return new ContextMessage(
                ContextMessage.Role.valueOf(message.role().name()),
                message.content(),
                null,
                toolCalls,
                message.toolCallId(),
                message.toolName());
    }

    @SuppressWarnings("unchecked")
    private ContextToolCall toContextToolCall(ModelToolCall call) {
        Map<String, Object> arguments = json.convertValue(call.arguments(), Map.class);
        return new ContextToolCall(call.toolCallId(), call.name(), arguments);
    }
}
