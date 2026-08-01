package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunEventMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentRunEventService
        extends AuditedServiceImpl<AgentRunEventMapper, AgentRunEventEntity> {}
