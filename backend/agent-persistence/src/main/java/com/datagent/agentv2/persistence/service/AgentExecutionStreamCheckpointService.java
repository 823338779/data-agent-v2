package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionStreamCheckpointMapper;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentExecutionStreamCheckpointService
        extends ServiceImpl<
                AgentExecutionStreamCheckpointMapper, AgentExecutionStreamCheckpointEntity> {

    private static final String EMPTY_ANSWER = "";
    private static final String EXISTS_RUNNING_EXECUTION =
            """
            select 1 from agent_execution execution
             where execution.tenant_id = agent_execution_stream_checkpoint.tenant_id
               and execution.execution_id = agent_execution_stream_checkpoint.execution_id
               and execution.status = {0}
            """;

    private final ConversationPersistenceMapper models;

    public boolean save(AgentExecutionStreamCheckpoint checkpoint) {
        return super.save(models.toEntity(checkpoint));
    }

    public AgentExecutionStreamCheckpoint findByExecutionId(String executionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionStreamCheckpointEntity::getExecutionId, executionId)
                        .one());
    }

    public List<AgentExecutionStreamCheckpoint> findByExecutionIds(
            Collection<String> executionIds) {
        return models.toStreamModels(
                lambdaQuery()
                        .in(AgentExecutionStreamCheckpointEntity::getExecutionId, executionIds)
                        .list());
    }

    public boolean beginModelInvocation(
            String executionId, long expectedVersion, long modelInvocationSequence) {
        AgentExecutionStreamCheckpointEntity changes = new AgentExecutionStreamCheckpointEntity();
        changes.setModelInvocationSequence(modelInvocationSequence);
        changes.setPhase(ExecutionPhase.CONNECTING_MODEL.value());
        changes.setOutputDeltaSequence(0L);
        changes.setAnswer(EMPTY_ANSWER);
        return baseMapper.update(
                        changes,
                        runningCheckpoint(executionId, expectedVersion)
                                .lt(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .setIncrBy(AgentExecutionStreamCheckpointEntity::getVersion, 1))
                == 1;
    }

    public boolean checkpoint(
            String executionId,
            long expectedVersion,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String answer,
            ExecutionPhase phase) {
        AgentExecutionStreamCheckpointEntity changes = new AgentExecutionStreamCheckpointEntity();
        changes.setAnswer(answer);
        changes.setPhase(phase.value());
        changes.setOutputDeltaSequence(outputDeltaSequence);
        return baseMapper.update(
                        changes,
                        runningCheckpoint(executionId, expectedVersion)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .lt(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getOutputDeltaSequence,
                                        outputDeltaSequence)
                                .setIncrBy(AgentExecutionStreamCheckpointEntity::getVersion, 1))
                == 1;
    }

    public boolean complete(
            String executionId, long expectedVersion, long modelInvocationSequence, String answer) {
        AgentExecutionStreamCheckpointEntity changes = new AgentExecutionStreamCheckpointEntity();
        changes.setPhase(ExecutionPhase.COMPLETED.value());
        changes.setAnswer(answer);
        return baseMapper.update(
                        changes,
                        runningCheckpoint(executionId, expectedVersion)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .setIncrBy(AgentExecutionStreamCheckpointEntity::getVersion, 1))
                == 1;
    }

    private static com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                    AgentExecutionStreamCheckpointEntity>
            runningCheckpoint(String executionId, long expectedVersion) {
        return Wrappers.lambdaUpdate(AgentExecutionStreamCheckpointEntity.class)
                .eq(AgentExecutionStreamCheckpointEntity::getExecutionId, executionId)
                .eq(AgentExecutionStreamCheckpointEntity::getVersion, expectedVersion)
                .exists(EXISTS_RUNNING_EXECUTION, ExecutionStatus.RUNNING.value());
    }
}
