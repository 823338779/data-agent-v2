package com.datagent.agentv2.conversation.runtime;

/**
 * 本机执行所需的最小、不可变上下文。
 *
 * <p>该对象会在提交事务后交给虚拟线程，因此只携带可以安全跨线程传递的标量快照，不持有 Entity、事务资源或请求上下文。
 *
 * @param tenantId 执行所属租户；虚拟线程启动后据此恢复 {@code TenantContext}
 * @param conversationSessionId 执行所属会话，用于限定消息和上下文查询范围
 * @param executionId 本次执行的业务唯一标识，也是进程内任务去重和取消的键
 * @param inputUpToMessageSequence 本次执行允许读取的最高消息序号，避免并发追加消息污染输入快照
 */
public record LocalAgentExecution(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long inputUpToMessageSequence) {}
