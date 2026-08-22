package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    private static final String EMPTY_ANSWER = "";
    private static final String PHASE_COMPLETED = "COMPLETED";
    private static final String PHASE_CONNECTING_MODEL = "CONNECTING_MODEL";
    private static final String STATUS_RUNNING = "running";
    private static final String EXISTS_OWNED_EXECUTION =
            """
            select 1 from agent_execution execution
             where execution.tenant_id = agent_execution_stream_checkpoint.tenant_id
               and execution.execution_id = agent_execution_stream_checkpoint.execution_id
               and execution.status = {0}
               and execution.owner_instance_id = {1}
               and execution.execution_attempt_number = {2}
            """;

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
        // 只有更新的 attempt/turn 坐标且当前 owner 仍有效时，才能重置流检查点。
        AgentExecutionStreamCheckpointEntity changes =
                new AgentExecutionStreamCheckpointEntity();
        changes.setExecutionAttemptNumber(executionAttemptNumber);
        changes.setModelInvocationSequence(modelInvocationSequence);
        changes.setPhase(PHASE_CONNECTING_MODEL);
        changes.setOutputDeltaSequence(0L);
        changes.setAnswer(EMPTY_ANSWER);
        changes.setCheckpointedAt(now);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(AgentExecutionStreamCheckpointEntity.class)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity::getExecutionId,
                                        executionId)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity::getVersion,
                                        expectedVersion)
                                .and(
                                        coordinate ->
                                                coordinate
                                                        .lt(
                                                                AgentExecutionStreamCheckpointEntity
                                                                        ::getExecutionAttemptNumber,
                                                                executionAttemptNumber)
                                                        .or(
                                                                sameAttempt ->
                                                                        sameAttempt
                                                                                .eq(
                                                                                        AgentExecutionStreamCheckpointEntity
                                                                                                ::getExecutionAttemptNumber,
                                                                                        executionAttemptNumber)
                                                                                .lt(
                                                                                        AgentExecutionStreamCheckpointEntity
                                                                                                ::getModelInvocationSequence,
                                                                                        modelInvocationSequence)))
                                .exists(
                                        EXISTS_OWNED_EXECUTION,
                                        STATUS_RUNNING,
                                        ownerInstanceId,
                                        executionAttemptNumber)
                                .setIncrBy(
                                        AgentExecutionStreamCheckpointEntity::getVersion, 1))
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
        // delta 序号只能前进，旧 owner 或旧 checkpoint version 均无法覆盖新状态。
        AgentExecutionStreamCheckpointEntity changes =
                new AgentExecutionStreamCheckpointEntity();
        changes.setAnswer(answer);
        changes.setPhase(phase);
        changes.setOutputDeltaSequence(outputDeltaSequence);
        changes.setCheckpointedAt(now);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(AgentExecutionStreamCheckpointEntity.class)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity::getExecutionId,
                                        executionId)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity::getVersion,
                                        expectedVersion)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getExecutionAttemptNumber,
                                        executionAttemptNumber)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .lt(
                                        AgentExecutionStreamCheckpointEntity::getOutputDeltaSequence,
                                        outputDeltaSequence)
                                .exists(
                                        EXISTS_OWNED_EXECUTION,
                                        STATUS_RUNNING,
                                        ownerInstanceId,
                                        executionAttemptNumber)
                                .setIncrBy(
                                        AgentExecutionStreamCheckpointEntity::getVersion, 1))
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
        // 完成操作沿用相同版本和 owner fencing，保证终态不会被旧流覆盖。
        AgentExecutionStreamCheckpointEntity changes =
                new AgentExecutionStreamCheckpointEntity();
        changes.setPhase(PHASE_COMPLETED);
        changes.setAnswer(answer);
        changes.setCheckpointedAt(now);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(AgentExecutionStreamCheckpointEntity.class)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity::getExecutionId,
                                        executionId)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getExecutionAttemptNumber,
                                        executionAttemptNumber)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity
                                                ::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .eq(
                                        AgentExecutionStreamCheckpointEntity::getVersion,
                                        expectedVersion)
                                .exists(
                                        EXISTS_OWNED_EXECUTION,
                                        STATUS_RUNNING,
                                        ownerInstanceId,
                                        executionAttemptNumber)
                                .setIncrBy(
                                        AgentExecutionStreamCheckpointEntity::getVersion, 1))
                == 1;
    }
}
