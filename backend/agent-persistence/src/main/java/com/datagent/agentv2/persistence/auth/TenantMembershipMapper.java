package com.datagent.agentv2.persistence.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface TenantMembershipMapper extends BaseMapper<TenantMembershipEntity> {}
