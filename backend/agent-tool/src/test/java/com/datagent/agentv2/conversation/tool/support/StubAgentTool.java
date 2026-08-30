package com.datagent.agentv2.conversation.tool.support;

import com.datagent.agentv2.conversation.tool.AgentTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tool 运行时测试使用的最小工具替身。 */
public record StubAgentTool(String name, String description, JsonNode parametersSchema)
        implements AgentTool {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public StubAgentTool(String name) {
        this(name, name, JSON.createObjectNode());
    }

    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) {
        return JSON.createObjectNode();
    }
}
