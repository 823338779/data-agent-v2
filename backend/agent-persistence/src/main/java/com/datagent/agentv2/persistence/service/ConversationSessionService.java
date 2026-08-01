package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import com.datagent.agentv2.persistence.service.mapper.ConversationSessionMapper;
import org.springframework.stereotype.Service;

@Service
public class ConversationSessionService
        extends ServiceImpl<ConversationSessionMapper, ConversationSessionEntity> {

    public ConversationSessionEntity lockById(String conversationSessionId) {
        return baseMapper.selectByIdForUpdate(conversationSessionId);
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

    public ConversationSessionEntity findByUuid(String conversationSessionId) {
        return lambdaQuery()
                .eq(ConversationSessionEntity::getConversationSessionId, conversationSessionId)
                .one();
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
