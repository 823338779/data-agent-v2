package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.enums.ExecutionFailureCode;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionMapper;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentExecutionService extends ServiceImpl<AgentExecutionMapper, AgentExecutionEntity> {

    private static final String LOCK_FOR_UPDATE = "FOR UPDATE";
    private final ConversationPersistenceMapper models;

    public AgentExecution lockById(String executionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getExecutionId, executionId)
                        .last(LOCK_FOR_UPDATE)
                        .one());
    }

    public AgentExecution lockActiveByConversationSessionId(String conversationSessionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .eq(AgentExecutionEntity::getStatus, ExecutionStatus.RUNNING.value())
                        .last(LOCK_FOR_UPDATE)
                        .one());
    }

    public AgentExecution findByExecutionId(String executionId) {
        return models.toModel(
                lambdaQuery().eq(AgentExecutionEntity::getExecutionId, executionId).one());
    }

    public AgentExecution findRequest(String conversationSessionId, String idempotencyKey) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .eq(AgentExecutionEntity::getRequestIdempotencyKey, idempotencyKey)
                        .one());
    }

    public boolean save(AgentExecution execution) {
        AgentExecutionEntity entity = models.toEntity(execution);
        boolean saved = super.save(entity);
        if (saved) {
            models.updateModel(entity, execution);
        }
        return saved;
    }

    public List<AgentExecution> findRunning(int limit) {
        return models.toExecutionModels(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getStatus, ExecutionStatus.RUNNING.value())
                        .orderByAsc(AgentExecutionEntity::getId)
                        .last("LIMIT " + Math.max(0, limit))
                        .list());
    }

    public boolean completeRunning(String executionId, long expectedVersion) {
        return updateRunningStatus(executionId, expectedVersion, ExecutionStatus.DONE, null, null);
    }

    public boolean failRunning(
            String executionId,
            long expectedVersion,
            ExecutionFailureCode failureCode,
            String failureDetail) {
        return updateRunningStatus(
                executionId, expectedVersion, ExecutionStatus.FAILED, failureCode, failureDetail);
    }

    public boolean cancelRunning(String executionId, long expectedVersion) {
        return updateRunningStatus(
                executionId, expectedVersion, ExecutionStatus.CANCELLED, null, null);
    }

    public boolean hasActiveExecution(String conversationSessionId) {
        return lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .eq(AgentExecutionEntity::getStatus, ExecutionStatus.RUNNING.value())
                        .count()
                > 0L;
    }

    private boolean updateRunningStatus(
            String executionId,
            long expectedVersion,
            ExecutionStatus status,
            ExecutionFailureCode failureCode,
            String failureDetail) {
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setStatus(status.value());
        changes.setFailureCode(failureCode == null ? null : failureCode.value());
        changes.setFailureDetail(failureDetail);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(AgentExecutionEntity.class)
                                .eq(AgentExecutionEntity::getExecutionId, executionId)
                                .eq(
                                        AgentExecutionEntity::getStatus,
                                        ExecutionStatus.RUNNING.value())
                                .eq(AgentExecutionEntity::getVersion, expectedVersion)
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    public List<AgentExecution> findByConversationSessionIdOrderByCreatedAtDesc(
            String conversationSessionId) {
        return models.toExecutionModels(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .orderByDesc(AgentExecutionEntity::getCreatedAt)
                        .list());
    }

    public List<AgentExecution> findByExecutionIds(Collection<String> executionIds) {
        return models.toExecutionModels(
                lambdaQuery().in(AgentExecutionEntity::getExecutionId, executionIds).list());
    }

    public List<AgentExecution> findByConversationSessionIdsOrderByCreatedAtDesc(
            Collection<String> conversationSessionIds) {
        return models.toExecutionModels(
                lambdaQuery()
                        .in(AgentExecutionEntity::getConversationSessionId, conversationSessionIds)
                        .orderByDesc(AgentExecutionEntity::getCreatedAt)
                        .list());
    }

    /** 与会话同时匹配，禁止复用其他会话execution取得分析构建。 */
    public java.util.UUID analysisBuild(String executionId, String sessionId) {
        var row =
                lambdaQuery()
                        .eq(AgentExecutionEntity::getExecutionId, executionId)
                        .eq(AgentExecutionEntity::getConversationSessionId, sessionId)
                        .one();
        if (row == null) throw new IllegalStateException("可信执行不属于当前会话");
        return row.getAnalysisBuildId() == null
                ? null
                : java.util.UUID.fromString(row.getAnalysisBuildId());
    }

    /** 仅首次写入；不推进执行版本，避免独立分析选择与取消/完成状态CAS竞争。 */
    @org.springframework.transaction.annotation.Transactional
    public java.util.UUID selectAnalysisBuildIfAbsent(
            String executionId, String sessionId, java.util.UUID buildId) {
        lambdaUpdate()
                .eq(AgentExecutionEntity::getExecutionId, executionId)
                .eq(AgentExecutionEntity::getConversationSessionId, sessionId)
                .isNull(AgentExecutionEntity::getAnalysisBuildId)
                .set(AgentExecutionEntity::getAnalysisBuildId, buildId.toString())
                .update();
        return analysisBuild(executionId, sessionId);
    }
}
