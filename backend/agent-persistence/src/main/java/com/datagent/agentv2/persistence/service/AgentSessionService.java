package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentSessionEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentSessionMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentSessionService extends ServiceImpl<AgentSessionMapper, AgentSessionEntity> {}
