package com.datagent.agentv2.conversation.runtime;

/**
 * 创建事务提交后触发本机执行。
 *
 * @param execution 已固化输入消息边界的本地执行快照
 */
public record LocalExecutionStarted(LocalAgentExecution execution) {}
