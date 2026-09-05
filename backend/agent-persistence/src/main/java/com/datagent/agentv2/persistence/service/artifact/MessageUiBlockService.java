package com.datagent.agentv2.persistence.service.artifact;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.artifact.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.mapper.artifact.MessageUiBlockMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** 消息 UI Block 的单表持久化服务。 */
@Service
public class MessageUiBlockService extends ServiceImpl<MessageUiBlockMapper, MessageUiBlockEntity> {

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
