package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.NotificationOutboxMessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationOutboxMessageMapper
        extends BaseMapper<NotificationOutboxMessageEntity> {}
