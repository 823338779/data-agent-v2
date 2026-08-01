package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.NotificationOutboxEntity;
import com.datagent.agentv2.persistence.service.mapper.NotificationOutboxMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxService
        extends ServiceImpl<NotificationOutboxMapper, NotificationOutboxEntity> {

    public boolean insertControl(NotificationOutboxEntity outbox) {
        if (outbox.getTenantId() == null) {
            outbox.setTenantId(TenantContext.requiredTenantId());
        }
        return baseMapper.insertControl(outbox) == 1;
    }
}
