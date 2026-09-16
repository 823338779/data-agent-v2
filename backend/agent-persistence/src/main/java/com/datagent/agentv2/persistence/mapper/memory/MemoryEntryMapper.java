package com.datagent.agentv2.persistence.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.memory.MemoryEntryEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryEntryMapper extends BaseMapper<MemoryEntryEntity> {}
