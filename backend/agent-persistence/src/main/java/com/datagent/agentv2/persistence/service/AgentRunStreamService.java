package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunStreamMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AgentRunStreamService extends ServiceImpl<AgentRunStreamMapper, AgentRunStreamEntity> {

    public AgentRunStreamEntity findByRunId(String runId) {
        return lambdaQuery().eq(AgentRunStreamEntity::getRunId, runId).one();
    }

    public boolean beginTurn(
            String runId,
            long expectedVersion,
            long executionAttempt,
            long modelTurnSequence,
            String ownerInstanceId,
            Instant now) {
        return baseMapper.beginTurn(
                        runId,
                        expectedVersion,
                        executionAttempt,
                        modelTurnSequence,
                        ownerInstanceId,
                        now)
                == 1;
    }

    public boolean checkpoint(
            String runId,
            long expectedVersion,
            long executionAttempt,
            long modelTurnSequence,
            String ownerInstanceId,
            long streamSequence,
            String reasoning,
            String answer,
            String phase,
            Instant reasoningExpiresAt,
            Instant now) {
        return baseMapper.checkpoint(
                        runId,
                        expectedVersion,
                        executionAttempt,
                        modelTurnSequence,
                        ownerInstanceId,
                        streamSequence,
                        reasoning,
                        answer,
                        phase,
                        reasoningExpiresAt,
                        now)
                == 1;
    }

    public boolean complete(
            String runId,
            long expectedVersion,
            long executionAttempt,
            long modelTurnSequence,
            String ownerInstanceId,
            String answer,
            Instant now) {
        return baseMapper.complete(
                        runId,
                        expectedVersion,
                        executionAttempt,
                        modelTurnSequence,
                        ownerInstanceId,
                        answer,
                        now)
                == 1;
    }
}
