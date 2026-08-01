package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.model.ModelToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public final class AgentToolRegistry {

    private static final String ERROR_DUPLICATE_TOOL = "Duplicate agent tool: ";
    private static final String ERROR_UNKNOWN_TOOL = "Unknown agent tool: ";

    private final Map<String, AgentTool> toolsByName;

    public AgentToolRegistry(List<AgentTool> registeredTools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : registeredTools) {
            if (indexed.put(tool.name(), tool) != null) {
                throw new IllegalStateException(ERROR_DUPLICATE_TOOL + tool.name());
            }
        }
        this.toolsByName = Map.copyOf(indexed);
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public AgentTool require(String name) {
        return find(name).orElseThrow(() -> new IllegalStateException(ERROR_UNKNOWN_TOOL + name));
    }

    public List<ModelToolDefinition> modelDefinitions() {
        return toolsByName.values().stream()
                .map(tool -> new ModelToolDefinition(tool.name(), tool.description(), schema(tool)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(AgentTool tool) {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .convertValue(tool.parametersSchema(), Map.class);
    }
}
