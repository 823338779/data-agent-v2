package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.MessageUiBlock;
import com.datagent.agentv2.persistence.entity.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.mapping.MessageUiBlockPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.MessageUiBlockMapper;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 消息 UI Block 的单表持久化服务。 */
@Service
@RequiredArgsConstructor
public class MessageUiBlockService extends ServiceImpl<MessageUiBlockMapper, MessageUiBlockEntity> {

    private final MessageUiBlockPersistenceMapper models;

    /** 批量恢复消息卡片及其 JSON 负载，保持数据库中的展示次序。 */
    public List<MessageUiBlock> findByMessageIds(Collection<String> messageIds) {
        return listByMessageIds(messageIds).stream().map(models::toModel).toList();
    }

    public List<MessageUiBlockEntity> listByMessageIds(Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return lambdaQuery()
                .in(MessageUiBlockEntity::getConversationMessageId, messageIds)
                .orderByAsc(MessageUiBlockEntity::getConversationMessageId)
                .orderByAsc(MessageUiBlockEntity::getDisplayOrder)
                .list();
    }
}
