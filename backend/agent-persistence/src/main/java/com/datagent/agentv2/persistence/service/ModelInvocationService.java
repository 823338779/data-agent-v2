package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.service.mapper.ModelInvocationMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ModelInvocationService
        extends ServiceImpl<ModelInvocationMapper, ModelInvocationEntity> {

    private static final String STATUS_ABANDONED = "abandoned";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_STREAMING = "streaming";
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";
    private static final String EXISTS_OWNED_EXECUTION =
            """
            select 1 from agent_execution execution
             where execution.tenant_id = model_invocation.tenant_id
               and execution.execution_id = model_invocation.execution_id
               and execution.status = {0}
               and execution.owner_instance_id = {1}
               and execution.execution_attempt_number = {2}
            """;
    private static final String EXISTS_RECOVERED_EXECUTION =
            """
            select 1 from agent_execution execution
             where execution.tenant_id = model_invocation.tenant_id
               and execution.execution_id = model_invocation.execution_id
               and execution.status = {0}
               and execution.owner_instance_id = {1}
               and execution.execution_attempt_number = {2}
               and execution.execution_attempt_number
                   > model_invocation.execution_attempt_number
            """;

    public long nextSequence(String executionId) {
        // 读取当前最大序号对应的单行，避免为简单聚合维护自定义 Mapper SQL。
        ModelInvocationEntity latest =
                lambdaQuery()
                        .eq(ModelInvocationEntity::getExecutionId, executionId)
                        .orderByDesc(ModelInvocationEntity::getModelInvocationSequence)
                        .last(QUERY_LIMIT_ONE)
                        .one();
        return latest == null ? 1L : latest.getModelInvocationSequence() + 1L;
    }

    public ModelInvocationEntity findModelInvocation(
            String executionId, long modelInvocationSequence) {
        return lambdaQuery()
                .eq(ModelInvocationEntity::getExecutionId, executionId)
                .eq(ModelInvocationEntity::getModelInvocationSequence, modelInvocationSequence)
                .one();
    }

    /** 按 Agent 执行内顺序读取全部模型调用，供恢复规划使用。 */
    public List<ModelInvocationEntity> findByExecutionId(String executionId) {
        return lambdaQuery()
                .eq(ModelInvocationEntity::getExecutionId, executionId)
                .orderByAsc(ModelInvocationEntity::getModelInvocationSequence)
                .list();
    }

    public boolean finishOwned(
            String executionId,
            long modelInvocationSequence,
            long executionAttemptNumber,
            long expectedVersion,
            String status,
            Instant now,
            String ownerInstanceId) {
        // 更新条件同时验证模型调用版本和当前 execution owner，拒绝旧 worker 写入。
        ModelInvocationEntity changes = new ModelInvocationEntity();
        changes.setStatus(status);
        changes.setCompletedAt(now);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(ModelInvocationEntity.class)
                                .eq(ModelInvocationEntity::getExecutionId, executionId)
                                .eq(
                                        ModelInvocationEntity::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .eq(
                                        ModelInvocationEntity::getExecutionAttemptNumber,
                                        executionAttemptNumber)
                                .eq(ModelInvocationEntity::getStatus, STATUS_STREAMING)
                                .eq(ModelInvocationEntity::getVersion, expectedVersion)
                                .exists(
                                        EXISTS_OWNED_EXECUTION,
                                        STATUS_RUNNING,
                                        ownerInstanceId,
                                        executionAttemptNumber)
                                .setIncrBy(ModelInvocationEntity::getVersion, 1))
                == 1;
    }

    public boolean abandonRecovered(
            String executionId,
            long modelInvocationSequence,
            long oldExecutionAttemptNumber,
            long expectedVersion,
            String newOwnerInstanceId,
            long newExecutionAttemptNumber,
            Instant now) {
        // 恢复归档只允许新 owner 关闭旧 attempt 的 streaming 调用。
        ModelInvocationEntity changes = new ModelInvocationEntity();
        changes.setStatus(STATUS_ABANDONED);
        changes.setCompletedAt(now);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(ModelInvocationEntity.class)
                                .eq(ModelInvocationEntity::getExecutionId, executionId)
                                .eq(
                                        ModelInvocationEntity::getModelInvocationSequence,
                                        modelInvocationSequence)
                                .eq(
                                        ModelInvocationEntity::getExecutionAttemptNumber,
                                        oldExecutionAttemptNumber)
                                .eq(ModelInvocationEntity::getVersion, expectedVersion)
                                .eq(ModelInvocationEntity::getStatus, STATUS_STREAMING)
                                .exists(
                                        EXISTS_RECOVERED_EXECUTION,
                                        STATUS_RUNNING,
                                        newOwnerInstanceId,
                                        newExecutionAttemptNumber)
                                .setIncrBy(ModelInvocationEntity::getVersion, 1))
                == 1;
    }
}
