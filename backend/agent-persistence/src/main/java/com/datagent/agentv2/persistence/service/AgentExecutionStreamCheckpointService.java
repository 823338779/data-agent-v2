package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionStreamCheckpointMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionStreamCheckpointService
        extends ServiceImpl<
                AgentExecutionStreamCheckpointMapper, AgentExecutionStreamCheckpointEntity> {

    private final ConversationPersistenceMapper models;

    public AgentExecutionStreamCheckpointService(ConversationPersistenceMapper models) {
        this.models = models;
    }

    public boolean save(AgentExecutionStreamCheckpoint checkpoint) {
        return super.save(models.toEntity(checkpoint));
    }

    public AgentExecutionStreamCheckpoint findByExecutionId(String executionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionStreamCheckpointEntity::getExecutionId, executionId)
                        .one());
    }

    /** 按执行标识批量读取最新检查点。 */
    public List<AgentExecutionStreamCheckpoint> findByExecutionIds(
            Collection<String> executionIds) {
        return models.toStreamModels(
                lambdaQuery()
                        .in(AgentExecutionStreamCheckpointEntity::getExecutionId, executionIds)
                        .list());
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
            String answer,
            String phase,
            Instant now) {
        return baseMapper.checkpoint(
                        executionId,
                        expectedVersion,
                        executionAttemptNumber,
                        modelInvocationSequence,
                        ownerInstanceId,
                        outputDeltaSequence,
                        answer,
                        phase,
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
}
