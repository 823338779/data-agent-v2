package com.datagent.agentv2.persistence.mapper.development;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.development.DevelopmentRecoveryEntity;
import org.apache.ibatis.annotations.Mapper;

/** 恢复回执的类型化持久访问。 */
@Mapper
public interface DevelopmentRecoveryMapper extends BaseMapper<DevelopmentRecoveryEntity> {}
