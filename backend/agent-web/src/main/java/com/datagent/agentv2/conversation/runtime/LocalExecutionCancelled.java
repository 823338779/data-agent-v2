package com.datagent.agentv2.conversation.runtime;

/**
 * 取消事务提交后中断对应的本机任务。
 *
 * @param executionId 需要从进程内注册表移除并中断的执行业务标识
 */
public record LocalExecutionCancelled(String executionId) {}
