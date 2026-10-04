package com.datagent.agentv2.persistence.mapper.git;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.git.GitExecutionWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;

/** GitExecutionWorkspace 的单表映射，只供持久化服务调用。 */
@Mapper
public interface GitExecutionWorkspaceMapper extends BaseMapper<GitExecutionWorkspaceEntity> {}
