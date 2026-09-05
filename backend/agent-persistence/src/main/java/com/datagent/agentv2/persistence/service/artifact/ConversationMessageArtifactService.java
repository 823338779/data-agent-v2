package com.datagent.agentv2.persistence.service.artifact;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.artifact.ConversationMessageArtifactEntity;
import com.datagent.agentv2.persistence.mapper.artifact.ConversationMessageArtifactMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** 消息与 Artifact 关系的单表持久化服务。 */
@Service
public class ConversationMessageArtifactService
        extends ServiceImpl<ConversationMessageArtifactMapper, ConversationMessageArtifactEntity> {

    public List<ConversationMessageArtifactEntity> listByMessageIds(Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return lambdaQuery()
                .in(ConversationMessageArtifactEntity::getConversationMessageId, messageIds)
                .orderByAsc(ConversationMessageArtifactEntity::getConversationMessageId)
                .orderByAsc(ConversationMessageArtifactEntity::getDisplayOrder)
                .list();
    }

    public List<ConversationMessageArtifactEntity> listByArtifactId(String artifactId) {
        return lambdaQuery()
                .eq(ConversationMessageArtifactEntity::getArtifactId, artifactId)
                .list();
    }
}
