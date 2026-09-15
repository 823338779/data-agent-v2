package com.datagent.agentv2.conversation.project;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.conversation.session.ConversationSummaryView;
import java.util.List;

/** 同一数据库快照内的项目和会话摘要，空项目仍在 projects 中保留。 */
public record ConversationSidebarView(
        List<ConversationProjectView> projects, List<ConversationSummaryView> sessions) {
    public static ConversationSidebarView composeFromProjectsAndSessions(
            List<ConversationProject> projects, List<ConversationSummaryView> sessions) {
        return new ConversationSidebarView(
                projects.stream().map(ConversationProjectView::composeFromProject).toList(),
                List.copyOf(sessions));
    }
}
