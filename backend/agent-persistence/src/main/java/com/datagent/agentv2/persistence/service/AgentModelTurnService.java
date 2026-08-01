package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentModelTurnEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentModelTurnMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentModelTurnService extends ServiceImpl<AgentModelTurnMapper, AgentModelTurnEntity> {

    public long nextSequence(String runId) {
        return baseMapper.maxSequence(runId) + 1;
    }

    public AgentModelTurnEntity findTurn(String runId, long modelTurnSequence) {
        return lambdaQuery()
                .eq(AgentModelTurnEntity::getRunId, runId)
                .eq(AgentModelTurnEntity::getModelTurnSequence, modelTurnSequence)
                .one();
    }

    /** 按 Run 内顺序读取全部 Model Turn，供恢复规划使用。 */
    public List<AgentModelTurnEntity> findByRunId(String runId) {
        return lambdaQuery()
                .eq(AgentModelTurnEntity::getRunId, runId)
                .orderByAsc(AgentModelTurnEntity::getModelTurnSequence)
                .list();
    }

    public boolean appendReasoning(
            String runId,
            long modelTurnSequence,
            long executionAttempt,
            long expectedVersion,
            String delta,
            Instant expiresAt,
            Instant now,
            String ownerInstanceId) {
        return baseMapper.appendReasoning(
                        runId,
                        modelTurnSequence,
                        executionAttempt,
                        expectedVersion,
                        delta,
                        expiresAt,
                        now,
                        ownerInstanceId)
                == 1;
    }

    public boolean finishOwned(
            String runId,
            long modelTurnSequence,
            long executionAttempt,
            long expectedVersion,
            String status,
            Instant now,
            String ownerInstanceId) {
        return baseMapper.finishOwned(
                        runId,
                        modelTurnSequence,
                        executionAttempt,
                        expectedVersion,
                        status,
                        now,
                        ownerInstanceId)
                == 1;
    }

    public boolean abandonRecovered(
            String runId,
            long modelTurnSequence,
            long oldExecutionAttempt,
            long expectedVersion,
            String newOwnerInstanceId,
            long newExecutionAttempt,
            Instant now) {
        return baseMapper.abandonRecovered(
                        runId,
                        modelTurnSequence,
                        oldExecutionAttempt,
                        expectedVersion,
                        newOwnerInstanceId,
                        newExecutionAttempt,
                        now)
                == 1;
    }
}
