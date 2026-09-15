package com.datagent.agentv2.git.port;

/** 将可信执行上下文转换为会话；模型不能指定会话或工作区路径。 */
@FunctionalInterface
public interface GitSessionResolver {
    String resolveSessionId(String executionId);
}
