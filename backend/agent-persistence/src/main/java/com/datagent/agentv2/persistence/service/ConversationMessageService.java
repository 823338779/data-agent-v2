package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.json.ConversationProcessDocumentCodec;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.ConversationMessageMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationMessageService
        extends ServiceImpl<ConversationMessageMapper, ConversationMessageEntity> {

    private static final String LOCK_FOR_UPDATE = "FOR UPDATE";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ConversationPersistenceMapper models;
    private final ConversationProcessDocumentCodec processDocuments;

    public ConversationMessageService(
            ConversationPersistenceMapper models,
            ConversationProcessDocumentCodec processDocuments) {
        this.models = models;
        this.processDocuments = processDocuments;
    }

    public boolean save(ConversationMessage message) {
        ConversationMessageEntity entity = models.toEntity(message);
        entity.setThinkingProcess(processDocuments.write(message.getThinkingProcess()));
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
        entity.setThinkingProcess(processDocuments.write(message.getThinkingProcess()));
        boolean updated = super.updateById(entity);
        if (updated) {
            models.updateModel(entity, message);
        }
        return updated;
    }

    /** 对外会话投影只返回真实 user/assistant 消息，并保持稳定序号。 */
    public List<ConversationMessage> findUserVisibleByConversationSessionId(
            String conversationSessionId) {
        return toModels(
                lambdaQuery()
                        .eq(
                                ConversationMessageEntity::getConversationSessionId,
                                conversationSessionId)
                        .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 读取一次 Agent 执行已经产生的用户可见消息，供 SSE 增量更新直接合并界面状态。 */
    public List<ConversationMessage> findUserVisibleByExecutionId(String executionId) {
        return toModels(
                lambdaQuery()
                        .eq(ConversationMessageEntity::getExecutionId, executionId)
                        .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
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

    /** 按执行读取真实 user/assistant 消息，并保持会话内顺序。 */
    public List<ConversationMessage> findByExecutionId(String executionId) {
        return toModels(
                lambdaQuery()
                        .eq(ConversationMessageEntity::getExecutionId, executionId)
                        .in(ConversationMessageEntity::getRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 模型上下文读取真实 user/assistant 消息，不再依赖 internal 日志。 */
    public List<ConversationMessage> findContextByExecutionIdUpTo(
            String executionId, long sequence) {
        return toModels(
                lambdaQuery()
                        .eq(ConversationMessageEntity::getExecutionId, executionId)
                        .le(ConversationMessageEntity::getConversationMessageSequence, sequence)
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

    /** 读取 Entity 并在唯一 codec 边界恢复强类型 ProcessItem 列表。 */
    private ConversationMessage toModel(ConversationMessageEntity entity) {
        if (entity == null) {
            return null;
        }
        ConversationMessage message = models.toModel(entity);
        message.setThinkingProcess(processDocuments.read(entity.getThinkingProcess()));
        return message;
    }

    /** 保持数据库查询顺序批量恢复强类型 ProcessItem 列表。 */
    private List<ConversationMessage> toModels(List<ConversationMessageEntity> entities) {
        return entities.stream().map(this::toModel).toList();
    }
}
