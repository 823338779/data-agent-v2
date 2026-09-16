package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.persistence.entity.memory.MemoryStateEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemoryStateMapper;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 记忆状态锁边界；调用方事务应先取得该行，再锁审查任务及租约。 */
@Service
@RequiredArgsConstructor
public class MemoryStateService extends ServiceImpl<MemoryStateMapper, MemoryStateEntity> {
    private final MemoryPersistenceMapper models;

    /** 为启用时间、恢复证据截止时间提供统一的北京时间数据库时钟。 */
    public java.time.LocalDateTime currentBusinessTime() {
        return baseMapper.currentBusinessTime();
    }

    /** 调用方已持有状态锁；CAS 兜底阻止意外越过统一写入边界的版本覆盖。 */
    public boolean advanceRevision(long expectedRevision) {
        return baseMapper.update(
                        MemoryStateEntity.create(),
                        Wrappers.lambdaUpdate(MemoryStateEntity.class)
                                .eq(MemoryStateEntity::getUserId, OperatorContext.requiredUserId())
                                .eq(MemoryStateEntity::getRevision, expectedRevision)
                                .set(MemoryStateEntity::getRevision, expectedRevision + 1))
                == 1;
    }

    public MemoryState lockCurrent() {
        var seed = MemoryStateEntity.create();
        // ON CONFLICT 避免并发初始化的唯一键异常污染当前事务。
        baseMapper.initialize(seed);
        var entity =
                lambdaQuery()
                        .eq(MemoryStateEntity::getUserId, OperatorContext.requiredUserId())
                        .last("FOR UPDATE")
                        .one();
        return models.toModel(entity);
    }
}
