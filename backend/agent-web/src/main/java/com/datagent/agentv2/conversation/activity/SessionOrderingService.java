package com.datagent.agentv2.conversation.activity;

import com.datagent.agentv2.persistence.entity.ConversationPreferenceEntity;
import com.datagent.agentv2.persistence.entity.ConversationSessionUserStateEntity;
import com.datagent.agentv2.persistence.service.ConversationPreferenceService;
import com.datagent.agentv2.persistence.service.ConversationSessionUserStateService;
import com.datagent.agentv2.platform.context.OperatorContext;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionOrderingService {

    private static final String ERROR_VISIBLE_SET_MISMATCH =
            "Submitted conversation ids must equal the complete visible set";

    private final ConversationPreferenceService preferences;
    private final ConversationSessionUserStateService states;

    @Transactional
    public OrderedSessions ensureVisibleSessions() {
        String userId = OperatorContext.requiredOperator().userId();
        preferences.ensure(userId);
        int inserted = states.ensureVisible(userId);
        ConversationPreferenceEntity preference = preferences.lockByUserId(userId);
        if (inserted > 0
                && !preferences.incrementOrderingVersion(userId, preference.getOrderingVersion())) {
            throw new SessionOrderConflictException();
        }
        return current(userId);
    }

    @Transactional
    public OrderedSessions initializeCreatedSession(String conversationSessionId) {
        String userId = OperatorContext.requiredOperator().userId();
        preferences.ensure(userId);
        ConversationPreferenceEntity preference = preferences.lockByUserId(userId);
        states.insertCreatedSession(userId, conversationSessionId);
        if (!preferences.incrementOrderingVersion(userId, preference.getOrderingVersion())) {
            throw new SessionOrderConflictException();
        }
        return current(userId);
    }

    @Transactional
    public OrderedSessions reorder(
            List<String> conversationSessionIds, long expectedOrderingVersion) {
        OrderedSessions visible = ensureVisibleSessions();
        String userId = OperatorContext.requiredOperator().userId();
        ConversationPreferenceEntity preference = preferences.lockByUserId(userId);
        if (preference.getOrderingVersion() != expectedOrderingVersion) {
            throw new SessionOrderConflictException();
        }

        // 完整集合校验同时拒绝遗漏、重复和跨租户注入的会话标识。
        List<String> visibleIds =
                visible.states().stream()
                        .map(ConversationSessionUserStateEntity::getConversationSessionId)
                        .toList();
        if (conversationSessionIds.size() != visibleIds.size()
                || new HashSet<>(conversationSessionIds).size() != conversationSessionIds.size()
                || !new HashSet<>(conversationSessionIds).equals(new HashSet<>(visibleIds))) {
            throw new IllegalArgumentException(ERROR_VISIBLE_SET_MISMATCH);
        }

        // 两阶段写回避免唯一排序位置在交换过程中短暂冲突。
        states.replaceOrder(userId, conversationSessionIds);
        if (!preferences.incrementOrderingVersion(userId, expectedOrderingVersion)) {
            throw new SessionOrderConflictException();
        }
        return current(userId);
    }

    private OrderedSessions current(String userId) {
        ConversationPreferenceEntity preference = preferences.findByUserId(userId);
        return new OrderedSessions(preference.getOrderingVersion(), states.listOrdered(userId));
    }

    public record OrderedSessions(
            long orderingVersion, List<ConversationSessionUserStateEntity> states) {}
}
