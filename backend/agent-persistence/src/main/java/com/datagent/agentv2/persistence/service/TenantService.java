package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.mapper.TenantMapper;
import com.datagent.agentv2.persistence.entity.TenantEntity;
import org.springframework.stereotype.Service;

@Service
public class TenantService extends AuditedServiceImpl<TenantMapper, TenantEntity> {}
