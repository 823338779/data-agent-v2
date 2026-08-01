package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentMessageMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentMessageService
        extends AuditedServiceImpl<AgentMessageMapper, AgentMessageEntity> {}
