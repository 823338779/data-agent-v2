package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.ConversationSessionMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationSessionService
        extends ServiceImpl<ConversationSessionMapper, ConversationSessionEntity> {

    private static final String LOCK_FOR_UPDATE = "FOR UPDATE";
    private static final String SET_LAST_ACTIVITY_AT =
            "last_activity_at = greatest(last_activity_at, {0})";
    private final ConversationPersistenceMapper models;

    public ConversationSession lockById(String conversationSessionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(
                                ConversationSessionEntity::getConversationSessionId,
                                conversationSessionId)
                        .last(LOCK_FOR_UPDATE)
                        .one());
    }

    public boolean save(ConversationSession session) {
        ConversationSessionEntity entity = models.toEntity(session);
        boolean saved = super.save(entity);
        if (saved) {
            models.updateModel(entity, session);
        }
        return saved;
    }

    public boolean updateById(ConversationSession session) {
        ConversationSessionEntity entity =
                lambdaQuery()
                        .eq(
                                ConversationSessionEntity::getConversationSessionId,
                                session.getConversationSessionId())
                        .one();
        if (entity == null) {
            return false;
        }
        models.updateEntity(session, entity);
        boolean updated = super.updateById(entity);
        if (updated) {
            models.updateModel(entity, session);
        }
        return updated;
    }

    public long nextExecutionEventSequence(String conversationSessionId) {
        // 以单条 Wrapper UPDATE 原子分配序号，同时维护乐观锁版本和审计时间。
        int updated =
                baseMapper.update(
                        new ConversationSessionEntity(),
                        Wrappers.lambdaUpdate(ConversationSessionEntity.class)
                                .eq(
                                        ConversationSessionEntity::getConversationSessionId,
                                        conversationSessionId)
                                .setIncrBy(ConversationSessionEntity::getExecutionEventSequence, 1)
                                .setIncrBy(ConversationSessionEntity::getVersion, 1));
        if (updated != 1) {
            throw new IllegalStateException("agent session not found: " + conversationSessionId);
        }
        ConversationSessionEntity session =
                lambdaQuery()
                        .eq(
                                ConversationSessionEntity::getConversationSessionId,
                                conversationSessionId)
                        .one();
        return session.getExecutionEventSequence();
    }

    public ConversationSession findByUuid(String conversationSessionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(
                                ConversationSessionEntity::getConversationSessionId,
                                conversationSessionId)
                        .one());
    }

    /** 按更新时间降序读取当前租户内的全部会话。 */
    public List<ConversationSession> listByUpdatedAtDesc() {
        return models.toSessionModels(
                lambdaQuery()
                        .orderByDesc(ConversationSessionEntity::getUpdatedAt)
                        .orderByDesc(ConversationSessionEntity::getConversationSessionId)
                        .list());
    }

    /** 更新共享活动时间，供会话摘要按最新活动收敛。 */
    public void recordActivity(String conversationSessionId, Instant occurredAt) {
        // greatest 保证乱序事件不会回退活动时间。
        int updated =
                baseMapper.update(
                        new ConversationSessionEntity(),
                        Wrappers.lambdaUpdate(ConversationSessionEntity.class)
                                .eq(
                                        ConversationSessionEntity::getConversationSessionId,
                                        conversationSessionId)
                                .setSql(SET_LAST_ACTIVITY_AT, occurredAt)
                                .setIncrBy(ConversationSessionEntity::getVersion, 1));
        if (updated != 1) {
            throw new IllegalStateException(
                    "conversation session not found: " + conversationSessionId);
        }
    }
}
