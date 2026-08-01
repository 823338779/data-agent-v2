package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunEventMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentRunEventService extends ServiceImpl<AgentRunEventMapper, AgentRunEventEntity> {}
