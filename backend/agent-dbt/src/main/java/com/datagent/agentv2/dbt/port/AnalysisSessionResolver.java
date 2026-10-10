package com.datagent.agentv2.dbt.port;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;

/** 宿主核实执行归属；模型参数不能提供或覆盖会话身份。 */
@FunctionalInterface
public interface AnalysisSessionResolver {
    String resolveSession(InvocationContext context)
            throws com.datagent.agentv2.conversation.exception.ToolBusinessException;
}
