package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunStreamMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentRunStreamService
        extends AuditedServiceImpl<AgentRunStreamMapper, AgentRunStreamEntity> {}
