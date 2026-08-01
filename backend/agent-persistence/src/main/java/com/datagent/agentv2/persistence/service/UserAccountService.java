package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.UserAccountEntity;
import com.datagent.agentv2.persistence.service.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService extends ServiceImpl<UserAccountMapper, UserAccountEntity> {}
