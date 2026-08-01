package com.datagent.agentv2.persistence.conversation;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AgentSessionService
        extends AuditedServiceImpl<AgentSessionMapper, AgentSessionEntity> {}
