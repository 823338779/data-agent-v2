package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionStreamCheckpointMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionStreamCheckpointService
        extends ServiceImpl<
                AgentExecutionStreamCheckpointMapper, AgentExecutionStreamCheckpointEntity> {

    public AgentExecutionStreamCheckpointEntity findByExecutionId(String executionId) {
        return lambdaQuery()
                .eq(AgentExecutionStreamCheckpointEntity::getExecutionId, executionId)
                .one();
    }

    public boolean beginModelInvocation(
            String executionId,
            long expectedVersion,
            long executionAttemptNumber,
            long modelInvocationSequence,
            String ownerInstanceId,
            Instant now) {
        return baseMapper.beginModelInvocation(
                        executionId,
                        expectedVersion,
                        executionAttemptNumber,
                        modelInvocationSequence,
                        ownerInstanceId,
                        now)
                == 1;
    }

    public boolean checkpoint(
            String executionId,
            long expectedVersion,
            long executionAttemptNumber,
            long modelInvocationSequence,
            String ownerInstanceId,
            long outputDeltaSequence,
            String reasoning,
            String answer,
            String phase,
            Instant reasoningExpiresAt,
            Instant now) {
        return baseMapper.checkpoint(
                        executionId,
                        expectedVersion,
                        executionAttemptNumber,
                        modelInvocationSequence,
                        ownerInstanceId,
                        outputDeltaSequence,
                        reasoning,
                        answer,
                        phase,
                        reasoningExpiresAt,
                        now)
                == 1;
    }

    public boolean complete(
            String executionId,
            long expectedVersion,
            long executionAttemptNumber,
            long modelInvocationSequence,
            String ownerInstanceId,
            String answer,
            Instant now) {
        return baseMapper.complete(
                        executionId,
                        expectedVersion,
                        executionAttemptNumber,
                        modelInvocationSequence,
                        ownerInstanceId,
                        answer,
                        now)
                == 1;
    }

    /** 清理一页已到期终态执行检查点 reasoning。 */
    public int clearExpiredReasoning(Instant now, int limit) {
        return baseMapper.clearExpiredReasoning(now, limit);
    }
}
