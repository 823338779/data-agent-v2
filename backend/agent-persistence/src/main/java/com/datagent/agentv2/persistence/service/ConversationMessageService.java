package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.json.ConversationProcessDocumentCodec;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.ConversationMessageMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationMessageService
        extends ServiceImpl<ConversationMessageMapper, ConversationMessageEntity> {

    private static final String VISIBILITY_USER = "user";

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

    /** 对外会话投影只返回 user visibility 消息，并保持稳定序号。 */
    public List<ConversationMessage> findUserVisibleByConversationSessionId(
            String conversationSessionId) {
        return toModels(
                lambdaQuery()
                        .eq(
                                ConversationMessageEntity::getConversationSessionId,
                                conversationSessionId)
                        .eq(ConversationMessageEntity::getVisibility, VISIBILITY_USER)
                        .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                        .list());
    }

    /** 模型上下文读取完整消息日志，不过滤 internal 消息。 */
    public List<ConversationMessage> findContextByExecutionIdUpTo(
            String executionId, long sequence) {
        return toModels(
                lambdaQuery()
                        .eq(ConversationMessageEntity::getExecutionId, executionId)
                        .le(ConversationMessageEntity::getConversationMessageSequence, sequence)
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
