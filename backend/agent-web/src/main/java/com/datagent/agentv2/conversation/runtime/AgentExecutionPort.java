package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.domain.AgentExecution;

/** 将已经领取并带有 fencing 信息的 Agent 执行交给具体执行引擎。 */
public interface AgentExecutionPort {

    void start(ClaimedAgentExecution execution, Runnable onTerminated);

    default void cancel(ClaimedAgentExecution execution) {}

    /**
     * 一次已经取得数据库 lease 的 Agent 执行。
     *
     * @param tenantId 执行所属租户的业务标识
     * @param executionId Agent 执行业务标识
     * @param ownerInstanceId 当前持有 lease 的工作实例标识
     * @param executionAttemptNumber 当前执行尝试编号，用于拒绝旧 owner 写入
     */
    record ClaimedAgentExecution(
            String tenantId,
            String executionId,
            String ownerInstanceId,
            long executionAttemptNumber) {

        /** 从租户、持久执行和本次 lease 信息组装执行认领。 */
        public static ClaimedAgentExecution composeFromLease(
                String tenantId,
                AgentExecution execution,
                String ownerInstanceId,
                long executionAttemptNumber) {
            return new ClaimedAgentExecution(
                    tenantId,
                    execution.getExecutionId(),
                    ownerInstanceId,
                    executionAttemptNumber);
        }
    }
}
