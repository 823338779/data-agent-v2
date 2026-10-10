package com.datagent.agentv2.dbt.port;

import java.util.UUID;

/** 从可信会话和执行解析固定构建；活动部署推进不得暗换已有选择。 */
@FunctionalInterface
public interface BuildContextResolver {
    UUID resolve(String sessionId, String executionId)
            throws com.datagent.agentv2.conversation.exception.ToolBusinessException;
}
