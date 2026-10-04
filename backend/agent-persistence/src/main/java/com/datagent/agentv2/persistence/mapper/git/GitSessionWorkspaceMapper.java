package com.datagent.agentv2.persistence.mapper.git;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.git.GitSessionWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;

/** GitSessionWorkspace 的单表映射，只供持久化服务调用。 */
@Mapper
public interface GitSessionWorkspaceMapper extends BaseMapper<GitSessionWorkspaceEntity> {}
