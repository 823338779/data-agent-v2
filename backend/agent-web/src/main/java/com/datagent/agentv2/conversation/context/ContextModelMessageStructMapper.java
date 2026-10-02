package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.util.List;
import java.util.Map;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 模型协议消息与供应商无关上下文消息之间的结构映射。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ContextModelMessageStructMapper {

    List<ContextMessage> toContextMessages(
            List<ModelInputMessage> messages, @Context ObjectMapper json);

    List<ModelInputMessage> toModelMessages(
            List<ContextMessage> messages, @Context ObjectMapper json);

    List<ContextToolDefinition> toContextToolDefinitions(List<ModelToolDefinition> tools);

    ContextToolDefinition toContextToolDefinition(ModelToolDefinition tool);

    ContextMessage toContextMessage(ModelInputMessage message, @Context ObjectMapper json);

    ModelInputMessage toModelMessage(ContextMessage message, @Context ObjectMapper json);

    @Mapping(target = "id", source = "toolCallId")
    ContextToolCall toContextToolCall(ModelToolCall call, @Context ObjectMapper json);

    @Mapping(target = "toolCallId", source = "id")
    ModelToolCall toModelToolCall(ContextToolCall call, @Context ObjectMapper json);

    @SuppressWarnings("unchecked")
    default Map<String, Object> toArguments(JsonNode arguments, @Context ObjectMapper json) {
        return json.convertValue(arguments, Map.class);
    }

    default JsonNode toArguments(Map<String, Object> arguments, @Context ObjectMapper json) {
        return json.valueToTree(arguments);
    }
}
