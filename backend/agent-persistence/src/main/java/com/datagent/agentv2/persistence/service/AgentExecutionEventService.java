package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.persistence.entity.AgentExecutionEventEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionEventMapper;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentExecutionEventService
        extends ServiceImpl<AgentExecutionEventMapper, AgentExecutionEventEntity> {

    /** Publishes successful persistence notifications to transaction-aware delivery adapters. */
    private final ApplicationEventPublisher applicationEvents;

    private final ConversationPersistenceMapper models;

    public boolean save(AgentExecutionEvent event) {
        // Publish only successful writes; transaction-aware listeners defer delivery until commit.
        AgentExecutionEventEntity entity = models.toEntity(event);
        boolean saved = super.save(entity);
        if (saved) {
            applicationEvents.publishEvent(
                    new AgentExecutionEventPersisted(entity.getTenantId(), models.toModel(entity)));
        }
        return saved;
    }

    /** 读取会话游标之后的事件。 */
    public List<AgentExecutionEvent> findByConversationSessionIdAfter(
            String conversationSessionId, long afterSequence) {
        return models.toEventModels(
                lambdaQuery()
                        .eq(
                                AgentExecutionEventEntity::getConversationSessionId,
                                conversationSessionId)
                        .gt(AgentExecutionEventEntity::getExecutionEventSequence, afterSequence)
                        .orderByAsc(AgentExecutionEventEntity::getExecutionEventSequence)
                        .list());
    }

    /** 批量读取会话集合中游标之后的事件。 */
    public List<AgentExecutionEvent> findByConversationSessionIdsAfter(
            Collection<String> conversationSessionIds, long afterSequence) {
        return models.toEventModels(
                lambdaQuery()
                        .in(
                                AgentExecutionEventEntity::getConversationSessionId,
                                conversationSessionIds)
                        .gt(AgentExecutionEventEntity::getExecutionEventSequence, afterSequence)
                        .orderByAsc(AgentExecutionEventEntity::getConversationSessionId)
                        .orderByAsc(AgentExecutionEventEntity::getExecutionEventSequence)
                        .list());
    }

    /** 按执行标识读取完整事件历史。 */
    public List<AgentExecutionEvent> findByExecutionId(String executionId) {
        return models.toEventModels(
                lambdaQuery()
                        .eq(AgentExecutionEventEntity::getExecutionId, executionId)
                        .orderByAsc(AgentExecutionEventEntity::getExecutionEventSequence)
                        .list());
    }
}
