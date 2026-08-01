package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.TenantMembershipEntity;
import com.datagent.agentv2.persistence.service.mapper.TenantMembershipMapper;
import org.springframework.stereotype.Service;

@Service
public class TenantMembershipService
        extends ServiceImpl<TenantMembershipMapper, TenantMembershipEntity> {}
