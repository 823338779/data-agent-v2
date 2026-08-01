package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.TenantMembershipEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMembershipMapper extends BaseMapper<TenantMembershipEntity> {}
