package com.datagent.agentv2.git.port;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.git.model.GitWorkspaceContext;

/** 工具从可信 executionId 解析固定工作区，不接受模型传入目标覆盖。 */
public interface GitWorkspaceResolver {
    GitWorkspaceContext resolve(InvocationContext context);
}
