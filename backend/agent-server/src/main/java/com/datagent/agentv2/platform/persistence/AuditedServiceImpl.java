package com.datagent.agentv2.platform.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class AuditedServiceImpl<M extends BaseMapper<T>, T extends AuditedEntity>
        extends ServiceImpl<M, T> {

    private Clock clock;

    @Autowired
    final void setAuditClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean removeById(T entity) {
        entity.setDeleted(true);
        entity.setDeletedAt(clock.instant());
        return super.removeById(entity);
    }
}
