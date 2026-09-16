package com.datagent.agentv2.memory.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.memory.MemoryException;
import com.datagent.agentv2.memory.MemoryOutcome;
import com.datagent.agentv2.memory.MemoryWriteContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 主 Agent 的个人记忆工具；身份、允许证据和冻结 revision 全部取自服务端 Registry。 */
@Component
@Order(130)
@RequiredArgsConstructor
public final class MemoryTool implements AgentTool {

    public static final String TOOL_ID = "memory";

    private static final String PARAMETERS_SCHEMA =
            """
            {"type":"object","oneOf":[
              {"additionalProperties":false,"required":["action","kind","content","write_reason","evidence_message_ids"],"properties":{"action":{"const":"add"},"kind":{"type":"string","enum":["person_profile","collaboration_preference"]},"content":{"type":"string","minLength":1},"write_reason":{"type":"string","enum":["user_requested","agent_inferred"]},"evidence_message_ids":{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string","minLength":1}},"restore":{"type":"boolean"}}},
              {"additionalProperties":false,"required":["action"],"properties":{"action":{"const":"list"},"kind":{"type":"string","enum":["person_profile","collaboration_preference"]},"cursor":{"type":"string","minLength":1},"limit":{"type":"integer","minimum":1,"maximum":100}}},
              {"additionalProperties":false,"required":["action","memory_id","expected_version","new_content","write_reason","evidence_message_ids"],"properties":{"action":{"const":"replace"},"memory_id":{"type":"string","minLength":1},"expected_version":{"type":"integer","minimum":1},"new_content":{"type":"string","minLength":1},"write_reason":{"type":"string","enum":["user_requested","agent_inferred"]},"evidence_message_ids":{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string","minLength":1}}}},
              {"additionalProperties":false,"required":["action","memory_id","expected_version","write_reason","evidence_message_ids"],"properties":{"action":{"const":"remove"},"memory_id":{"type":"string","minLength":1},"expected_version":{"type":"integer","minimum":1},"write_reason":{"type":"string","enum":["user_requested","agent_inferred"]},"evidence_message_ids":{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string","minLength":1}}}}
            ]}
            """;

    private final MemoryToolDispatcher dispatcher;
    private final MemoryInvocationRegistry registry;
    private final ObjectMapper json;

    @Override
    public String name() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return MemoryToolPrompts.MAIN;
    }

    @Override
    public JsonNode parametersSchema() {
        return json.readTree(PARAMETERS_SCHEMA);
    }

    @Override
    public boolean supportsIdempotency() {
        return true;
    }

    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) throws Exception {
        String action = action(arguments);
        if ("list".equals(action)) {
            // 先确认调用属于已打开的可信轮次；list 读取最新快照，但绝不替换冻结写入 revision。
            registry.snapshotFor(context);
            return dispatcher.list(arguments);
        }

        registry.requireAllowedEvidence(context, dispatcher.evidenceIds(arguments));
        MemoryWriteContext writeContext = registry.contextFor(context);
        long expectedRevision = writeContext.expectedRevision();
        MemoryOutcome outcome;
        try {
            outcome = dispatcher.dispatch(writeContext, arguments);
        } catch (MemoryException failure) {
            throw new ToolBusinessException(
                    failure.code().name(), failure.safeMessage(), failureDetails(failure));
        }
        if (!outcome.successful()) {
            throw new ToolBusinessException(
                    outcome.status().name(), "记忆操作未完成", json.valueToTree(outcome));
        }
        registry.advance(
                context.executionId(),
                context.modelInvocationSequence(),
                expectedRevision,
                outcome.revision());
        return json.valueToTree(outcome);
    }

    private JsonNode failureDetails(MemoryException failure) {
        return json.createObjectNode().put("status", failure.code().name());
    }

    private static String action(JsonNode arguments) {
        if (arguments == null
                || !arguments.isObject()
                || !arguments.path("action").isTextual()
                || arguments.path("action").asString().isBlank()) {
            throw new IllegalArgumentException("action must be a non-blank string");
        }
        return arguments.path("action").asString();
    }
}
