package com.datagent.agentv2.development;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 执行记录是会话身份的唯一来源；不读取模型提供的目标标识。 */
@Component
@RequiredArgsConstructor
public final class TrustedAnalysisSessionResolver implements AnalysisSessionResolver {
    /** 持久执行查询。 */
    private final AgentExecutionService executions;

    @Override
    public String resolveSession(InvocationContext context)
            throws com.datagent.agentv2.conversation.exception.ToolBusinessException {
        if (context == null || context.executionId() == null || context.executionId().isBlank())
            throw missing();
        var execution = executions.findByExecutionId(context.executionId());
        if (execution == null || execution.getConversationSessionId() == null) throw missing();
        return execution.getConversationSessionId();
    }

    private static com.datagent.agentv2.conversation.exception.ToolBusinessException missing() {
        return new com.datagent.agentv2.conversation.exception.ToolBusinessException(
                "DEVELOPMENT_EXECUTION_NOT_FOUND", "可信执行不存在");
    }
}
