package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.entity.ConversationSessionUserStateEntity;
import com.datagent.agentv2.persistence.service.mapper.ConversationSessionUserStateMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationSessionUserStateService {

    private final ConversationSessionUserStateMapper mapper;
    private final ConversationSessionService sessions;

    /** 返回当前租户内指定用户的全部会话状态。 */
    public List<ConversationSessionUserStateEntity> listByUserId(String userId) {
        return mapper.selectList(
                Wrappers.lambdaQuery(ConversationSessionUserStateEntity.class)
                        .eq(
                                ConversationSessionUserStateEntity::getTenantId,
                                TenantContext.requiredTenantId())
                        .eq(ConversationSessionUserStateEntity::getUserId, userId)
                        .orderByAsc(
                                ConversationSessionUserStateEntity::getConversationSessionId));
    }

    /** 补齐当前用户尚未建立状态的可见会话。 */
    public int ensureVisible(String userId) {
        return mapper.insertMissingVisible(TenantContext.requiredTenantId(), userId);
    }

    /** 单调推进用户对指定会话的已读游标。 */
    public void advanceReadCursor(String userId, String conversationSessionId, long upToSequence) {
        // 先读取共享水位，再以游标小于目标值为条件提交原子 Wrapper 更新。
        ConversationSession session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            return;
        }
        long targetSequence = Math.min(upToSequence, session.getLastNotifiableEventSequence());
        ConversationSessionUserStateEntity changes = new ConversationSessionUserStateEntity();
        changes.setLastReadEventSequence(targetSequence);
        changes.setUpdatedAt(java.time.Instant.now());
        mapper.update(
                changes,
                Wrappers.lambdaUpdate(ConversationSessionUserStateEntity.class)
                        .eq(
                                ConversationSessionUserStateEntity::getTenantId,
                                TenantContext.requiredTenantId())
                        .eq(ConversationSessionUserStateEntity::getUserId, userId)
                        .eq(
                                ConversationSessionUserStateEntity::getConversationSessionId,
                                conversationSessionId)
                        .lt(
                                ConversationSessionUserStateEntity::getLastReadEventSequence,
                                targetSequence)
                        .setIncrBy(ConversationSessionUserStateEntity::getVersion, 1));
    }
}
