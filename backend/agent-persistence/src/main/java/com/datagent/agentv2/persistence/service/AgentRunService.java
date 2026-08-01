package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentRunService extends AuditedServiceImpl<AgentRunMapper, AgentRunEntity> {}
