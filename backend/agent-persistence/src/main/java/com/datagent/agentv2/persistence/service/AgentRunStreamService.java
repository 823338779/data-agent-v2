package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunStreamMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentRunStreamService
        extends ServiceImpl<AgentRunStreamMapper, AgentRunStreamEntity> {}
