package com.datagent.agentv2.development;

import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.conversation.session.ConversationView;
import com.datagent.agentv2.development.application.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.DevelopmentSelection;
import com.datagent.agentv2.development.model.DevelopmentException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 先在事务外读取Git基线，再原子创建会话与固定开发绑定。 */
@Service
@RequiredArgsConstructor
public class DevelopmentSessionCreationService {
    /** 原有普通会话用例。 */
    private final ConversationApplicationService conversations;

    /** 开发绑定。 */
    private final DevelopmentContextService contexts;

    /** Git关闭时普通会话仍可使用。 */
    private final ObjectProvider<DevelopmentBranchService> branches;

    /** 仅包裹数据库变更，远端I/O在此之前完成。 */
    private final TransactionTemplate transactions;

    public ConversationView create(
            String title, String tag, String projectId, DevelopmentSelection selection) {
        if (selection == null) return conversations.create(title, tag, projectId);
        if (projectId != null && !projectId.equals(selection.projectId()))
            throw new DevelopmentException("DEVELOPMENT_CONFLICT", "会话项目与开发目标不一致", 409);
        var service = branches.getIfAvailable();
        if (service == null)
            throw new DevelopmentException("DEVELOPMENT_UNAVAILABLE", "Git开发能力未启用", 503);
        var observedConfig = contexts.config(selection.projectId());
        var branch =
                service.list(selection.projectId()).stream()
                        .filter(item -> item.branchName().equals(selection.branchName()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new DevelopmentException(
                                                "DEVELOPMENT_BRANCH_NOT_FOUND", "开发分支不存在", 404));
        return transactions.execute(
                status -> {
                    var created = conversations.create(title, tag, selection.projectId());
                    contexts.bind(
                            created.conversationSessionId(),
                            selection.projectId(),
                            selection.branchName(),
                            branch.headSha(),
                            observedConfig);
                    return created;
                });
    }
}
