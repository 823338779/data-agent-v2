package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentToolExecutionMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentToolExecutionService
        extends ServiceImpl<AgentToolExecutionMapper, AgentToolExecutionEntity> {

    /** 读取 Run 的全部工具执行，供上下文一致性校验和恢复规划使用。 */
    public List<AgentToolExecutionEntity> findByRunId(String runId) {
        return lambdaQuery()
                .eq(AgentToolExecutionEntity::getRunId, runId)
                .orderByAsc(AgentToolExecutionEntity::getId)
                .list();
    }

    /** 按业务标识读取 execution 当前版本。 */
    public AgentToolExecutionEntity findByToolExecutionId(String toolExecutionId) {
        return lambdaQuery()
                .eq(AgentToolExecutionEntity::getToolExecutionId, toolExecutionId)
                .one();
    }

    /** 以 owner、attempt 和 execution version fencing 开始或恢复一次实际工具调用。 */
    public boolean startExecution(
            String toolExecutionId,
            long expectedVersion,
            Instant startedAt,
            String idempotencyKey,
            String ownerInstanceId,
            long executionAttempt) {
        return baseMapper.startExecution(
                        TenantContext.requiredTenantId(),
                        toolExecutionId,
                        expectedVersion,
                        startedAt,
                        idempotencyKey,
                        ownerInstanceId,
                        executionAttempt)
                == 1;
    }

    /** 以相同 fencing 条件把 execution 和唯一 Observation 一起提升为 terminal。 */
    public boolean completeExecution(
            String toolExecutionId,
            long expectedVersion,
            String status,
            String observationMessageId,
            Instant completedAt,
            String failureCode,
            String ownerInstanceId,
            long executionAttempt) {
        return baseMapper.completeExecution(
                        TenantContext.requiredTenantId(),
                        toolExecutionId,
                        expectedVersion,
                        status,
                        observationMessageId,
                        completedAt,
                        failureCode,
                        ownerInstanceId,
                        executionAttempt)
                == 1;
    }

    /** 按 Model Turn 和创建顺序读取工具执行，保证严格串行恢复。 */
    public List<AgentToolExecutionEntity> findByTurn(String runId, long modelTurnSequence) {
        return lambdaQuery()
                .eq(AgentToolExecutionEntity::getRunId, runId)
                .eq(AgentToolExecutionEntity::getModelTurnSequence, modelTurnSequence)
                .orderByAsc(AgentToolExecutionEntity::getId)
                .list();
    }
}
