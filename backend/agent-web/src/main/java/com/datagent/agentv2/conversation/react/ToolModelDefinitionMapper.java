package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.tool.AgentTool;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 把当前 execution 可见 Tool 映射为模型协议定义，并保持输入顺序。 */
@Component
public final class ToolModelDefinitionMapper {
    private final ObjectMapper json;

    public ToolModelDefinitionMapper(ObjectMapper json) {
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    public List<ModelToolDefinition> toModelDefinitions(List<AgentTool> tools) {
        return tools.stream()
                .map(
                        tool ->
                                new ModelToolDefinition(
                                        tool.name(),
                                        tool.description(),
                                        json.convertValue(tool.parametersSchema(), Map.class)))
                .toList();
    }
}
