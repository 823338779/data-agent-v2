package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunAttemptEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunAttemptMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import org.springframework.stereotype.Service;

@Service
public class AgentRunAttemptService
        extends ServiceImpl<AgentRunAttemptMapper, AgentRunAttemptEntity> {

    private static final String ID_PREFIX_RUN_ATTEMPT = "run_attempt";

    private final IdGenerator ids;

    public AgentRunAttemptService(AgentRunAttemptMapper mapper, IdGenerator ids) {
        this.baseMapper = mapper;
        this.ids = ids;
    }

    public boolean insert(AgentRunAttemptEntity attempt) {
        if (attempt.getTenantId() == null) {
            attempt.setTenantId(TenantContext.requiredTenantId());
        }
        if (attempt.getUuid() == null) {
            attempt.setUuid(ids.next(ID_PREFIX_RUN_ATTEMPT));
        }
        return baseMapper.insert(attempt) == 1;
    }

    public boolean finish(
            String runId,
            long executionAttempt,
            String status,
            java.time.Instant endedAt,
            String failureCode) {
        return baseMapper.finish(runId, executionAttempt, status, endedAt, failureCode) == 1;
    }
}
