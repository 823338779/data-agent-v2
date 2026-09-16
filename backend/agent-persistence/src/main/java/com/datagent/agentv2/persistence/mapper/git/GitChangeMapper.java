package com.datagent.agentv2.persistence.mapper.git;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;
import org.apache.ibatis.annotations.Mapper;

/** GitChange 单表映射，不暴露给业务模块。 */
@Mapper
public interface GitChangeMapper extends BaseMapper<GitChangeEntity> {}
