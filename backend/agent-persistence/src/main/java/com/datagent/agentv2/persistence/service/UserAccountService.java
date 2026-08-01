package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.AuditedServiceImpl;
import com.datagent.agentv2.persistence.entity.UserAccountEntity;
import com.datagent.agentv2.persistence.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService extends AuditedServiceImpl<UserAccountMapper, UserAccountEntity> {}
