package com.datagent.agentv2.conversation.model.ollama;

import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import java.util.List;
import java.util.Map;
import org.mapstruct.Builder;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.ai.ollama.api.OllamaApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 内部模型协议与 Ollama 请求协议之间的结构映射。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, builder = @Builder(disableBuilder = true))
public interface OllamaModelStructMapper {

    OllamaModelStructMapper INSTANCE = Mappers.getMapper(OllamaModelStructMapper.class);

    @Mapping(target = "images", ignore = true)
    @Mapping(
            target = "toolCalls",
            expression = "java(toProviderToolCalls(message.toolCalls(), json))")
    OllamaApi.Message toProviderMessage(ModelInputMessage message, @Context ObjectMapper json);

    @Mapping(target = "id", source = "toolCallId")
    @Mapping(
            target = "function",
            expression =
                    "java(new OllamaApi.Message.ToolCallFunction(call.name(), toMap(call.arguments(), json)))")
    OllamaApi.Message.ToolCall toProviderToolCall(ModelToolCall call, @Context ObjectMapper json);

    default List<OllamaApi.Message.ToolCall> toProviderToolCalls(
            List<ModelToolCall> calls, @Context ObjectMapper json) {
        return calls == null || calls.isEmpty()
                ? null
                : calls.stream().map(call -> toProviderToolCall(call, json)).toList();
    }

    default List<OllamaApi.ChatRequest.Tool> toProviderTools(List<ModelToolDefinition> tools) {
        return tools.stream().map(this::toProviderTool).toList();
    }

    default OllamaApi.ChatRequest.Tool toProviderTool(ModelToolDefinition tool) {
        return new OllamaApi.ChatRequest.Tool(
                new OllamaApi.ChatRequest.Tool.Function(
                        tool.name(), tool.description(), tool.parameters()));
    }

    @SuppressWarnings("unchecked")
    default Map<String, Object> toMap(JsonNode node, @Context ObjectMapper json) {
        return json.convertValue(node, Map.class);
    }
}
