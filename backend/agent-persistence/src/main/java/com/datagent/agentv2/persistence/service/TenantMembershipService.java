package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.mapper.TenantMembershipMapper;
import com.datagent.agentv2.persistence.entity.TenantMembershipEntity;
import org.springframework.stereotype.Service;

@Service
public class TenantMembershipService
        extends AuditedServiceImpl<TenantMembershipMapper, TenantMembershipEntity> {}
