package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentExecutionAttemptEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionAttemptMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionAttemptService
        extends ServiceImpl<AgentExecutionAttemptMapper, AgentExecutionAttemptEntity> {

    /** 执行尝试业务标识前缀，用于区分执行本身与同一执行内的接管尝试。 */
    private static final String ID_PREFIX_EXECUTION_ATTEMPT = "execution_attempt";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_WAITING = "waiting";

    private final IdGenerator ids;

    public AgentExecutionAttemptService(AgentExecutionAttemptMapper mapper, IdGenerator ids) {
        this.baseMapper = mapper;
        this.ids = ids;
    }

    public boolean insert(AgentExecutionAttemptEntity attempt) {
        if (attempt.getTenantId() == null) {
            attempt.setTenantId(TenantContext.requiredTenantId());
        }
        if (attempt.getExecutionAttemptId() == null) {
            attempt.setExecutionAttemptId(ids.next(ID_PREFIX_EXECUTION_ATTEMPT));
        }
        return baseMapper.insert(attempt) == 1;
    }

    public boolean finish(
            String executionId,
            long executionAttemptNumber,
            String status,
            Instant endedAt,
            String failureCode) {
        // 由 Wrapper 保留状态 fencing，并通过数据库原子自增避免并发覆盖版本号。
        AgentExecutionAttemptEntity changes = new AgentExecutionAttemptEntity();
        changes.setStatus(status);
        changes.setEndedAt(endedAt);
        changes.setFailureCode(failureCode);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(AgentExecutionAttemptEntity.class)
                                .eq(AgentExecutionAttemptEntity::getExecutionId, executionId)
                                .eq(
                                        AgentExecutionAttemptEntity::getExecutionAttemptNumber,
                                        executionAttemptNumber)
                                .in(
                                        AgentExecutionAttemptEntity::getStatus,
                                        STATUS_RUNNING,
                                        STATUS_WAITING)
                                .setIncrBy(AgentExecutionAttemptEntity::getVersion, 1))
                == 1;
    }
}
