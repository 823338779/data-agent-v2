package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRunEventService extends ServiceImpl<AgentRunEventMapper, AgentRunEventEntity> {

    /** Publishes successful persistence notifications to transaction-aware delivery adapters. */
    private final ApplicationEventPublisher applicationEvents;

    @Override
    public boolean save(AgentRunEventEntity event) {
        // Publish only successful writes; transaction-aware listeners defer delivery until commit.
        boolean saved = super.save(event);
        if (saved) {
            applicationEvents.publishEvent(new AgentRunEventSaved(event));
        }
        return saved;
    }
}
