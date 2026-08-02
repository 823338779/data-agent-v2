package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.ConversationSessionMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationSessionService
        extends ServiceImpl<ConversationSessionMapper, ConversationSessionEntity> {

    private final ConversationPersistenceMapper models;

    public ConversationSessionService(ConversationPersistenceMapper models) {
        this.models = models;
    }

    public ConversationSession lockById(String conversationSessionId) {
        return models.toModel(baseMapper.selectByIdForUpdate(conversationSessionId));
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
        if (baseMapper.incrementExecutionEventSequence(conversationSessionId) != 1) {
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

    /** 原子分配下一条不可变消息序号，供工具 Observation 和模型结果事务使用。 */
    public long nextConversationMessageSequence(String conversationSessionId) {
        if (baseMapper.incrementConversationMessageSequence(conversationSessionId) != 1) {
            throw new IllegalStateException("agent session not found: " + conversationSessionId);
        }
        ConversationSessionEntity session =
                lambdaQuery()
                        .eq(
                                ConversationSessionEntity::getConversationSessionId,
                                conversationSessionId)
                        .one();
        return session.getConversationMessageSequence();
    }

    public ConversationSession findByUuid(String conversationSessionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(
                                ConversationSessionEntity::getConversationSessionId,
                                conversationSessionId)
                        .one());
    }

    /** 按业务标识批量读取会话，并在持久化边界内完成模型转换。 */
    public List<ConversationSession> findByConversationSessionIds(
            Collection<String> conversationSessionIds) {
        return models.toSessionModels(
                lambdaQuery()
                        .in(
                                ConversationSessionEntity::getConversationSessionId,
                                conversationSessionIds)
                        .list());
    }

    /** 更新共享活动时间，并在需要时推进可通知事件水位。 */
    public void recordActivity(
            String conversationSessionId, Long notifiableSequence, java.time.Instant occurredAt) {
        if (baseMapper.recordActivity(conversationSessionId, notifiableSequence, occurredAt) != 1) {
            throw new IllegalStateException(
                    "conversation session not found: " + conversationSessionId);
        }
    }
}
