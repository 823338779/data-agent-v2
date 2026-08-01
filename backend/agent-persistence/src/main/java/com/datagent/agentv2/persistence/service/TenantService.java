package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.TenantEntity;
import com.datagent.agentv2.persistence.service.mapper.TenantMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TenantService extends ServiceImpl<TenantMapper, TenantEntity> {

    public List<String> activeTenantIds() {
        return lambdaQuery()
                .eq(TenantEntity::getStatus, "active")
                .orderByAsc(TenantEntity::getUuid)
                .list()
                .stream()
                .map(TenantEntity::getUuid)
                .toList();
    }

    public TenantEntity findByUuid(String tenantId) {
        return lambdaQuery().eq(TenantEntity::getUuid, tenantId).one();
    }
}
