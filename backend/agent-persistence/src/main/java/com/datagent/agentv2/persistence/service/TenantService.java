package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.TenantEntity;
import com.datagent.agentv2.persistence.service.mapper.TenantMapper;
import org.springframework.stereotype.Service;

@Service
public class TenantService extends ServiceImpl<TenantMapper, TenantEntity> {

    public TenantEntity findByUuid(String tenantId) {
        return lambdaQuery().eq(TenantEntity::getTenantId, tenantId).one();
    }
}
