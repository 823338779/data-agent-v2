package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.mapper.ToolExecutionMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ToolExecutionService extends ServiceImpl<ToolExecutionMapper, ToolExecutionEntity> {

    /** 读取 Agent 执行的全部工具执行，供上下文一致性校验和恢复规划使用。 */
    public List<ToolExecutionEntity> findByExecutionId(String executionId) {
        return lambdaQuery()
                .eq(ToolExecutionEntity::getExecutionId, executionId)
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
        return baseMapper.startExecution(
                        TenantContext.requiredTenantId(),
                        toolExecutionId,
                        expectedVersion,
                        startedAt,
                        idempotencyKey,
                        ownerInstanceId,
                        executionAttemptNumber)
                == 1;
    }

    /** 以相同 fencing 条件把 execution 和唯一 Observation 一起提升为 terminal。 */
    public boolean completeExecution(
            String toolExecutionId,
            long expectedVersion,
            String status,
            String observationConversationMessageId,
            Instant completedAt,
            String failureCode,
            String ownerInstanceId,
            long executionAttemptNumber) {
        return baseMapper.completeExecution(
                        TenantContext.requiredTenantId(),
                        toolExecutionId,
                        expectedVersion,
                        status,
                        observationConversationMessageId,
                        completedAt,
                        failureCode,
                        ownerInstanceId,
                        executionAttemptNumber)
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
