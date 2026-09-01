package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.ConversationMessageMapper;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationMessageService
        extends ServiceImpl<ConversationMessageMapper, ConversationMessageEntity> {

    private static final String LOCK_FOR_UPDATE = "FOR UPDATE";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ConversationPersistenceMapper models;

    public boolean save(ConversationMessage message) {
        ConversationMessageEntity entity = models.toEntity(message);
        boolean saved = super.save(entity);
        if (saved) {
            models.updateModel(entity, message);
        }
        return saved;
    }

    public boolean updateById(ConversationMessage message) {
        ConversationMessageEntity entity =
                lambdaQuery()
                        .eq(
                                ConversationMessageEntity::getConversationMessageId,
                                message.getConversationMessageId())
                        .one();
        if (entity == null) {
            return false;
        }
        models.updateEntity(message, entity);
        boolean updated = super.updateById(entity);
        if (updated) {
            models.updateModel(entity, message);
        }
        return updated;
    }

    /** 锁定一次执行的 assistant 中心消息，供正文和过程列表串行更新。 */
    public ConversationMessage lockAssistantByExecutionId(String executionId) {
        return toModel(
                lambdaQuery()
                        .eq(ConversationMessageEntity::getExecutionId, executionId)
                        .eq(ConversationMessageEntity::getRole, ROLE_ASSISTANT)
                        .last(LOCK_FOR_UPDATE)
                        .one());
    }

    /** 按会话与序号边界读取真实 user/assistant 消息，用于构造模型上下文。 */
    public List<ConversationMessage> findByConversationSessionIdUpTo(
            String conversationSessionId, long sequence) {
        return toModels(
                lambdaQuery()
                        .eq(
                                ConversationMessageEntity::getConversationSessionId,
                                conversationSessionId)
                        .le(ConversationMessageEntity::getConversationMessageSequence, sequence)
                        .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 按会话读取全部真实 user/assistant 中心消息，供不使用消息上界的上下文策略重建历史。 */
    public List<ConversationMessage> findByConversationSessionId(String conversationSessionId) {
        return toModels(
                lambdaQuery()
                        .eq(
                                ConversationMessageEntity::getConversationSessionId,
                                conversationSessionId)
                        .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 按执行读取真实 user/assistant 消息，并保持会话内顺序。 */
    public List<ConversationMessage> findByExecutionId(String executionId) {
        return toModels(
                lambdaQuery()
                        .eq(ConversationMessageEntity::getExecutionId, executionId)
                        .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 按业务标识读取单条消息，供 execution 与 assistant call 建立一致性边界。 */
    public ConversationMessage findByConversationMessageId(String conversationMessageId) {
        return toModel(
                lambdaQuery()
                        .eq(
                                ConversationMessageEntity::getConversationMessageId,
                                conversationMessageId)
                        .one());
    }

    /** 一次查询读取指定会话集合中的全部 assistant 消息，供 SSE reconciliation 选取最新中心消息。 */
    public List<ConversationMessage> findAssistantsByConversationSessionIds(
            Collection<String> conversationSessionIds) {
        if (conversationSessionIds.isEmpty()) {
            return List.of();
        }
        return toModels(
                lambdaQuery()
                        .in(
                                ConversationMessageEntity::getConversationSessionId,
                                conversationSessionIds)
                        .eq(ConversationMessageEntity::getRole, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 读取 Entity 并恢复领域消息。 */
    private ConversationMessage toModel(ConversationMessageEntity entity) {
        return models.toModel(entity);
    }

    /** 保持数据库查询顺序批量恢复领域消息。 */
    private List<ConversationMessage> toModels(List<ConversationMessageEntity> entities) {
        return models.toMessageModels(entities);
    }
}
