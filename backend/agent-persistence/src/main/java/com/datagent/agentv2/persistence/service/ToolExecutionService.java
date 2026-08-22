package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.mapper.ToolExecutionMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ToolExecutionService extends ServiceImpl<ToolExecutionMapper, ToolExecutionEntity> {

    private static final String STATUS_EXECUTING = "executing";
    private static final String STATUS_PREPARED = "prepared";
    private static final String STATUS_RUNNING = "running";
    private static final String SET_IDEMPOTENCY_KEY_IF_ABSENT =
            "idempotency_key = coalesce(idempotency_key, {0})";
    private static final String EXISTS_OWNED_EXECUTION =
            """
            select 1 from agent_execution execution
             where execution.tenant_id = tool_execution.tenant_id
               and execution.execution_id = tool_execution.execution_id
               and execution.status = {0}
               and execution.owner_instance_id = {1}
               and execution.execution_attempt_number = {2}
            """;

    /** 读取 Agent 执行的全部工具执行，供上下文一致性校验和恢复规划使用。 */
    public List<ToolExecutionEntity> findByExecutionId(String executionId) {
        return lambdaQuery()
                .eq(ToolExecutionEntity::getExecutionId, executionId)
                .orderByAsc(ToolExecutionEntity::getId)
                .list();
    }

    /** 批量读取模型上下文中所有历史执行的工具行，避免多轮会话只索引当前 execution。 */
    public List<ToolExecutionEntity> findByExecutionIds(Collection<String> executionIds) {
        if (executionIds.isEmpty()) {
            return List.of();
        }
        return lambdaQuery()
                .in(ToolExecutionEntity::getExecutionId, executionIds)
                .orderByAsc(ToolExecutionEntity::getId)
                .list();
    }

    /** 按业务标识读取 execution 当前版本。 */
    public ToolExecutionEntity findByToolExecutionId(String toolExecutionId) {
        return lambdaQuery().eq(ToolExecutionEntity::getToolExecutionId, toolExecutionId).one();
    }

    /** 以 owner、attempt 和 execution version fencing 开始或恢复一次实际工具调用。 */
    public boolean startExecution(
            String toolExecutionId,
            long expectedVersion,
            Instant startedAt,
            String idempotencyKey,
            String ownerInstanceId,
            long executionAttemptNumber) {
        // 原子状态迁移同时执行 execution owner fencing，避免旧 attempt 重放工具调用。
        ToolExecutionEntity changes = new ToolExecutionEntity();
        changes.setStatus(STATUS_EXECUTING);
        changes.setExecutionAttemptNumber(executionAttemptNumber);
        changes.setStartedAt(startedAt);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(ToolExecutionEntity.class)
                                .eq(
                                        ToolExecutionEntity::getTenantId,
                                        TenantContext.requiredTenantId())
                                .eq(ToolExecutionEntity::getToolExecutionId, toolExecutionId)
                                .eq(ToolExecutionEntity::getVersion, expectedVersion)
                                .in(
                                        ToolExecutionEntity::getStatus,
                                        STATUS_PREPARED,
                                        STATUS_EXECUTING)
                                .exists(
                                        EXISTS_OWNED_EXECUTION,
                                        STATUS_RUNNING,
                                        ownerInstanceId,
                                        executionAttemptNumber)
                                .setIncrBy(ToolExecutionEntity::getExecutionCount, 1)
                                .setSql(SET_IDEMPOTENCY_KEY_IF_ABSENT, idempotencyKey)
                                .setIncrBy(ToolExecutionEntity::getVersion, 1))
                == 1;
    }

    /** 以相同 fencing 条件把 execution 与中心消息 TOOL 节点一起提升为 terminal。 */
    public boolean completeExecution(
            String toolExecutionId,
            long expectedVersion,
            String status,
            Instant completedAt,
            String failureCode,
            String ownerInstanceId,
            long executionAttemptNumber) {
        // 终态写入沿用相同 owner fencing，并通过期望版本拒绝并发完成。
        ToolExecutionEntity changes = new ToolExecutionEntity();
        changes.setStatus(status);
        changes.setFailureCode(failureCode);
        changes.setCompletedAt(completedAt);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(ToolExecutionEntity.class)
                                .eq(
                                        ToolExecutionEntity::getTenantId,
                                        TenantContext.requiredTenantId())
                                .eq(ToolExecutionEntity::getToolExecutionId, toolExecutionId)
                                .eq(ToolExecutionEntity::getVersion, expectedVersion)
                                .eq(ToolExecutionEntity::getStatus, STATUS_EXECUTING)
                                .exists(
                                        EXISTS_OWNED_EXECUTION,
                                        STATUS_RUNNING,
                                        ownerInstanceId,
                                        executionAttemptNumber)
                                .setIncrBy(ToolExecutionEntity::getVersion, 1))
                == 1;
    }

    /** 按 Model Turn 和创建顺序读取工具执行，保证严格串行恢复。 */
    public List<ToolExecutionEntity> findByTurn(String executionId, long modelInvocationSequence) {
        return lambdaQuery()
                .eq(ToolExecutionEntity::getExecutionId, executionId)
                .eq(ToolExecutionEntity::getModelInvocationSequence, modelInvocationSequence)
                .orderByAsc(ToolExecutionEntity::getId)
                .list();
    }
}
