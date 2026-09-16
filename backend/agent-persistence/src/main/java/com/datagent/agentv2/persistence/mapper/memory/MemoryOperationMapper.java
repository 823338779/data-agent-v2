package com.datagent.agentv2.persistence.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.memory.MemoryOperationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryOperationMapper extends BaseMapper<MemoryOperationEntity> {}
