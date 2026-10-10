package com.datagent.agentv2.persistence.mapper.development;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.development.DevelopmentBuildAttemptEntity;
import org.apache.ibatis.annotations.Mapper;

/** 复用 MyBatis-Plus 单表持久能力。 */
@Mapper
public interface DevelopmentBuildAttemptMapper extends BaseMapper<DevelopmentBuildAttemptEntity> {}
