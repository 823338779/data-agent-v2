package com.datagent.agentv2.development.tool;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.development.application.PreviewBuildService;
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
public final class DevelopmentPreviewTool implements AgentTool {
    public static final String TOOL_ID = "development_preview";
    private static final String DESCRIPTION = "保存必要修改后构建并部署当前会话分支预览；受理后后台独立继续。";
    private static final String SCHEMA =
"""
{"type":"object","additionalProperties":false,"required":["expectedRevision"],"properties":{"expectedRevision":{"type":"integer","minimum":0},"commitMessage":{"type":"string","maxLength":1000}}}
""";
    private static final String REVISION = "expectedRevision";
    private static final String MESSAGE = "commitMessage";

    /** 持久业务用例。 */
    private final PreviewBuildService service;

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
                            new PreviewRequest(
                                    context.idempotencyKey(),
                                    args.path(REVISION).asLong(),
                                    args.has(MESSAGE) ? args.path(MESSAGE).asString() : null)));
        } catch (com.datagent.agentv2.development.model.DevelopmentException failure) {
            throw new com.datagent.agentv2.conversation.exception.ToolBusinessException(
                    failure.getCode(), failure.getMessage());
        }
    }
}
