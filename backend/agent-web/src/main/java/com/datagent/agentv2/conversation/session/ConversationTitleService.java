package com.datagent.agentv2.conversation.session;

import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 负责会话标题规范化及受版本保护的持久化更新。 */
@Service
@RequiredArgsConstructor
public class ConversationTitleService {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final String EMPTY_TEXT = "";
    private static final String ERROR_INVALID_TITLE =
            "Conversation title must contain 1-120 characters";
    private static final String ERROR_NOT_FOUND = "Conversation session not found";
    private static final String ERROR_VERSION_CONFLICT = "Conversation session version is stale";

    private final ConversationSessionService sessions;

    @Transactional
    public void rename(String conversationSessionId, String title, long expectedVersion) {
        // 在当前租户边界内读取会话，并拒绝不可见或已经删除的业务标识。
        ConversationSession session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_NOT_FOUND);
        }

        // 请求版本必须与读取快照一致，避免多端标题编辑互相覆盖。
        if (session.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_VERSION_CONFLICT);
        }

        // 服务层重复执行规范化和长度校验，确保非 HTTP 调用也遵守标题约束。
        String normalizedTitle = title == null ? EMPTY_TEXT : title.trim();
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_TITLE);
        }
        session.setTitle(normalizedTitle);

        // MyBatis-Plus 乐观锁负责封闭读取与写入之间的并发窗口。
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_VERSION_CONFLICT);
        }
    }
}
