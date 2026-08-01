package com.datagent.agentv2.persistence.service;

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

    /** 返回当前租户内指定用户的完整稳定顺序。 */
    public List<ConversationSessionUserStateEntity> listOrdered(String userId) {
        return mapper.selectOrderedByUserId(TenantContext.requiredTenantId(), userId);
    }

    /** 补齐当前用户尚未建立状态的可见会话。 */
    public int ensureVisible(String userId) {
        return mapper.insertMissingVisible(TenantContext.requiredTenantId(), userId);
    }

    /** 建立创建者状态并把新会话置顶。 */
    public void insertCreatedSession(String userId, String conversationSessionId) {
        String tenantId = TenantContext.requiredTenantId();
        mapper.shiftForNewSession(tenantId, userId);
        mapper.insertCreatedSession(tenantId, userId, conversationSessionId);
    }

    /** 单调推进用户对指定会话的已读游标。 */
    public void advanceReadCursor(String userId, String conversationSessionId, long upToSequence) {
        mapper.advanceReadCursor(
                TenantContext.requiredTenantId(), userId, conversationSessionId, upToSequence);
    }

    /** 使用临时区间后按客户端完整列表写回连续顺序。 */
    public void replaceOrder(String userId, List<String> conversationSessionIds) {
        String tenantId = TenantContext.requiredTenantId();
        mapper.moveToTemporaryRange(tenantId, userId, conversationSessionIds.size() + 1L);
        for (int index = 0; index < conversationSessionIds.size(); index++) {
            mapper.updateSortOrder(
                    tenantId, userId, conversationSessionIds.get(index), (long) index);
        }
    }
}
