package com.datagent.agentv2.conversation.project;

import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 侧栏一次读取的事务边界，避免项目删除前后的两组数据拼成不一致列表。 */
@Service
@RequiredArgsConstructor
public class ConversationSidebarQueryService {
    private final ConversationProjectService projects;
    private final ConversationApplicationService conversations;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConversationSidebarView getSidebar() {
        return ConversationSidebarView.composeFromProjectsAndSessions(
                projects.listByCreatedAtAsc(), conversations.listSummaries());
    }
}
