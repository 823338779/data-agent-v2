package com.datagent.agentv2.skill.tool;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.conversation.tool.runtime.ToolActivationResult;
import com.datagent.agentv2.skill.runtime.ExecutionSkillView;
import com.datagent.agentv2.skill.runtime.ExecutionSkillViews;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 读取 bundled Skill 资源，并在读取主指令时原子激活其受限 Tool 依赖。 */
@Component
@Order(115)
@RequiredArgsConstructor
public final class SkillReadTool implements AgentTool {
    private static final Logger LOG = LoggerFactory.getLogger("agent.skill.read");
    private static final String SCHEMA =
            """
            {"type":"object","additionalProperties":false,"required":["logical_id"],"properties":{"logical_id":{"type":"string","minLength":1},"path":{"type":"string","default":"SKILL.md"}}}
            """;
    private final ExecutionSkillViews skillViews;
    private final ObjectProvider<ExecutionToolViews> toolViews;
    private final ObjectMapper json;

    @Override
    public String name() {
        return "skill_read";
    }

    @Override
    public String description() {
        return "Read bundled skill instructions and activate their approved tools for this execution.";
    }

    @Override
    public JsonNode parametersSchema() {
        return json.readTree(SCHEMA);
    }

    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) throws Exception {
        JsonNode idNode = arguments.path("logical_id");
        if (!idNode.isTextual() || idNode.asString().isBlank())
            throw new IllegalArgumentException("logical_id must be a nonblank string");
        String logicalId = idNode.asString();
        JsonNode pathNode = arguments.path("path");
        String path = pathNode.isMissingNode() ? "SKILL.md" : pathNode.asString();
        ExecutionSkillView.ReadResult read =
                skillViews.require(context.executionId()).read(logicalId, path);
        List<String> activatedTools = List.of();
        boolean activated = false;
        boolean alreadyActivated = false;
        if ("SKILL.md".equals(path)) {
            ToolActivationResult activation =
                    toolViews
                            .getObject()
                            .require(context.executionId())
                            .activateAll(read.descriptor().requiredTools());
            activatedTools = activation.activatedToolNames();
            alreadyActivated = activation.alreadyActivated();
            activated = !activatedTools.isEmpty() && !alreadyActivated;
        }
        ObjectNode output = json.createObjectNode();
        output.put("logicalId", logicalId);
        output.put("path", path);
        output.put("digest", read.digest());
        output.put("content", read.content());
        ArrayNode names = output.putArray("activatedTools");
        activatedTools.forEach(names::add);
        output.put("activated", activated);
        output.put("alreadyActivated", alreadyActivated);
        LOG.info(
                "executionId={} logicalId={} digest={} activatedTools={} resultCode=success",
                context.executionId(),
                logicalId,
                read.digest(),
                activatedTools);
        return output;
    }
}
