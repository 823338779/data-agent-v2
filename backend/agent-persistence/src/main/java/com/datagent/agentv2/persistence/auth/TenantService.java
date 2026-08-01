package com.datagent.agentv2.persistence.auth;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TenantService extends AuditedServiceImpl<TenantMapper, TenantEntity> {}
