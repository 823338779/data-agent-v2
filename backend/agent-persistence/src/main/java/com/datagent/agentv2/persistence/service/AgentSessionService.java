package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentSessionEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentSessionMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentSessionService extends ServiceImpl<AgentSessionMapper, AgentSessionEntity> {

    public AgentSessionEntity lockById(String sessionId) {
        return baseMapper.selectByIdForUpdate(sessionId);
    }

    public long nextEventSequence(String sessionId) {
        if (baseMapper.incrementEventSequence(sessionId) != 1) {
            throw new IllegalStateException("agent session not found: " + sessionId);
        }
        AgentSessionEntity session =
                lambdaQuery().eq(AgentSessionEntity::getSessionId, sessionId).one();
        return session.getEventSequence();
    }

    public AgentSessionEntity findByUuid(String sessionId) {
        return lambdaQuery().eq(AgentSessionEntity::getSessionId, sessionId).one();
    }
}
