package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.service.mapper.ConversationMessageMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationMessageService
        extends ServiceImpl<ConversationMessageMapper, ConversationMessageEntity> {

    private static final String VISIBILITY_USER = "user";

    /** 对外会话投影只返回 user visibility 消息，并保持稳定序号。 */
    public List<ConversationMessageEntity> findUserVisibleByConversationSessionId(
            String conversationSessionId) {
        return lambdaQuery()
                .eq(ConversationMessageEntity::getConversationSessionId, conversationSessionId)
                .eq(ConversationMessageEntity::getVisibility, VISIBILITY_USER)
                .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                .list();
    }

    /** 模型上下文读取完整消息日志，不过滤 internal 消息。 */
    public List<ConversationMessageEntity> findContextByExecutionIdUpTo(
            String executionId, long sequence) {
        return lambdaQuery()
                .eq(ConversationMessageEntity::getExecutionId, executionId)
                .le(ConversationMessageEntity::getConversationMessageSequence, sequence)
                .orderByAsc(ConversationMessageEntity::getConversationMessageSequence)
                .list();
    }

    /** 按业务标识读取单条消息，供 execution 与 assistant call 建立一致性边界。 */
    public ConversationMessageEntity findByConversationMessageId(String conversationMessageId) {
        return lambdaQuery()
                .eq(ConversationMessageEntity::getConversationMessageId, conversationMessageId)
                .one();
    }
}
