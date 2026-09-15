package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.ConversationProjectEntity;
import org.apache.ibatis.annotations.Mapper;

/** 项目单表 CRUD，查询及更新条件由持久化服务的 Lambda Wrapper 维护。 */
@Mapper
public interface ConversationProjectMapper extends BaseMapper<ConversationProjectEntity> {}
