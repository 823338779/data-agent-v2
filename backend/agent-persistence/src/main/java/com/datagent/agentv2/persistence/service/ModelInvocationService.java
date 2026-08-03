package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.service.mapper.ModelInvocationMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ModelInvocationService
        extends ServiceImpl<ModelInvocationMapper, ModelInvocationEntity> {

    public long nextSequence(String executionId) {
        return baseMapper.maxSequence(executionId) + 1;
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
        return baseMapper.finishOwned(
                        executionId,
                        modelInvocationSequence,
                        executionAttemptNumber,
                        expectedVersion,
                        status,
                        now,
                        ownerInstanceId)
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
        return baseMapper.abandonRecovered(
                        executionId,
                        modelInvocationSequence,
                        oldExecutionAttemptNumber,
                        expectedVersion,
                        newOwnerInstanceId,
                        newExecutionAttemptNumber,
                        now)
                == 1;
    }
}
