package com.datagent.agentv2.development.tool;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.development.application.SourceSaveService;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 将模型动作交给BFF同一应用用例；目标只能从可信execution解析。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public final class DevelopmentSaveTool implements AgentTool {
    public static final String TOOL_ID = "development_save";
    private static final String DESCRIPTION = "保存完整工程到Git；成功只表示已推送，不代表已部署。";
    private static final String SCHEMA =
"""
{"type":"object","additionalProperties":false,"required":["expectedRevision","commitMessage"],"properties":{"expectedRevision":{"type":"integer","minimum":0},"commitMessage":{"type":"string","minLength":1,"maxLength":1000}}}
""";
    private static final String REVISION = "expectedRevision";
    private static final String MESSAGE = "commitMessage";

    /** 持久业务用例。 */
    private final SourceSaveService service;

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
                            new SaveRequest(
                                    context.idempotencyKey(),
                                    args.path(REVISION).asLong(),
                                    args.path(MESSAGE).asString())));
        } catch (com.datagent.agentv2.development.model.DevelopmentException failure) {
            throw new com.datagent.agentv2.conversation.exception.ToolBusinessException(
                    failure.getCode(), failure.getMessage());
        }
    }
}
