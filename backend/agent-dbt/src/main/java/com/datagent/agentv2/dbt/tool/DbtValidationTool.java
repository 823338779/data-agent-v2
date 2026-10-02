package com.datagent.agentv2.dbt.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.application.DbtValidationService;
import com.datagent.agentv2.dbt.model.DbtToolOutput;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.git.port.GitSessionResolver;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 验证工具只通过可信 execution 定位会话；不接收 YAML、远端或会话 ID。 */
@RequiredArgsConstructor
public final class DbtValidationTool implements AgentTool {
    public static final List<String> TOOL_IDS =
            List.of("dbt_validation_submit", "dbt_validation_get");
    private static final String SUBMIT_SCHEMA =
            """
        {"type":"object","required":["expectedRevision"],"additionalProperties":false,"properties":{"expectedRevision":{"type":"integer","minimum":0,"maximum":2147483647}}}
        """;
    private static final String GET_SCHEMA =
            """
        {"type":"object","required":["validationId"],"additionalProperties":false,"properties":{"validationId":{"type":"string","minLength":1,"maxLength":64}}}
        """;
    private final boolean submit;
    private final DbtValidationService service;
    private final GitSessionResolver sessions;
    private final ObjectMapper json;

    @Override
    public String name() {
        return TOOL_IDS.get(submit ? 0 : 1);
    }

    @Override
    public String description() {
        return submit ? "冻结当前草稿并提交 dbt 定义校验；不会构建、发布或推送。" : "读取当前会话的验证结果；只有当前快照 valid=true 才可准备提交。";
    }

    @Override
    public JsonNode parametersSchema() {
        return json.readTree(submit ? SUBMIT_SCHEMA : GET_SCHEMA);
    }

    @Override
    public boolean supportsIdempotency() {
        return true;
    }

    @Override
    public Duration timeout() {
        return Duration.ofMinutes(5);
    }

    @Override
    public JsonNode execute(JsonNode args, InvocationContext context) throws Exception {
        DbtAnalysisTool.validate(args, parametersSchema());
        if (context == null || context.executionId() == null || context.executionId().isBlank())
            throw new IllegalArgumentException("缺少可信执行身份");
        String session = sessions.resolveSessionId(context.executionId());
        try {
            return DbtToolOutput.bounded(
                    submit
                            ? service.submit(session, args.path("expectedRevision").asLong())
                            : service.get(session, args.path("validationId").asString()),
                    json);
        } catch (GitException error) {
            throw new ToolBusinessException(error.code(), "草稿范围、版本或 YAML 检查未通过，请核对后重试");
        }
    }
}
