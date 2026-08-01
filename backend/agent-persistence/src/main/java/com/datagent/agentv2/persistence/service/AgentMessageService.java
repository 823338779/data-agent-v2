package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentMessageMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentMessageService extends ServiceImpl<AgentMessageMapper, AgentMessageEntity> {}
