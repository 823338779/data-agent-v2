package com.datagent.agentv2.development.tool;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.development.application.DeliveryService;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 将模型动作交给BFF同一应用用例；目标只能从可信execution解析。 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${data-agent.git.enabled:false} and ${data-agent.dbt.enabled:false}")
public final class DevelopmentDeliverTool implements AgentTool {
    public static final String TOOL_ID = "development_deliver";
    private static final String DESCRIPTION = "按用户确认的完整共享分支差异与两端SHA合并并发布；范围变化必须重新确认。";
    private static final String SCHEMA =
"""
{"type":"object","additionalProperties":false,"required":["expectedSourceSha","expectedMainSha","confirmedScopeDigest"],"properties":{"expectedSourceSha":{"type":"string","minLength":40,"maxLength":64},"expectedMainSha":{"type":"string","minLength":40,"maxLength":64},"confirmedScopeDigest":{"type":"string","minLength":64,"maxLength":64}}}
""";
    private static final String SOURCE = "expectedSourceSha";
    private static final String MAIN = "expectedMainSha";
    private static final String DIGEST = "confirmedScopeDigest";

    /** 持久业务用例。 */
    private final DeliveryService service;

    /** 执行归属查询。 */
    private final AnalysisSessionResolver sessions;

    /** 协议序列化器。 */
    private final ObjectMapper json;

    @Override
    public String name() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode parametersSchema() {
        return json.readTree(SCHEMA);
    }

    @Override
    public boolean supportsIdempotency() {
        return true;
    }

    @Override
    public JsonNode execute(JsonNode args, InvocationContext context)
            throws com.datagent.agentv2.conversation.exception.ToolBusinessException {
        DevelopmentToolSupport.validate(args, parametersSchema());
        if (context == null
                || context.executionId() == null
                || context.executionId().isBlank()
                || context.idempotencyKey() == null
                || context.idempotencyKey().isBlank())
            throw new IllegalArgumentException("缺少可信执行或稳定动作键");
        try {
            return json.valueToTree(
                    service.start(
                            sessions.resolveSession(context),
                            new DeliverRequest(
                                    context.idempotencyKey(),
                                    args.path(SOURCE).asString(),
                                    args.path(MAIN).asString(),
                                    args.path(DIGEST).asString())));
        } catch (com.datagent.agentv2.development.model.DevelopmentException failure) {
            throw new com.datagent.agentv2.conversation.exception.ToolBusinessException(
                    failure.getCode(), failure.getMessage());
        }
    }
}
