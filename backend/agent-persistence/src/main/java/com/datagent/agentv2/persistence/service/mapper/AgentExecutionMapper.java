package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentExecutionMapper extends BaseMapper<AgentExecutionEntity> {}
