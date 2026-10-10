package com.datagent.agentv2.conversation.project;

import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 只改变侧栏归属与置顶，不访问会话正文或执行过程。 */
@Service
@RequiredArgsConstructor
public class ConversationOrganizationService {
    private final ConversationProjectService projects;
    private final ConversationSessionService sessions;
    private final com.datagent.agentv2.persistence.service.development
                    .DevelopmentContextPersistenceService
            development;

    @Transactional
    public ConversationSession setOrganization(
            String sessionId, String projectId, boolean pinned, long expectedVersion) {
        if (expectedVersion < 0
                || (projectId != null && (projectId.isBlank() || projectId.length() > 64))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid conversation organization");
        }
        // 锁顺序统一为目标项目再会话；删除持同一项目锁，杜绝删除后的新增关联。
        if (projectId != null && projects.lockByProjectId(projectId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        var session = sessions.lockById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Conversation session not found");
        }
        if (session.getVersion() != expectedVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Conversation session version is stale");
        }
        var bound = development.session(sessionId);
        if (bound != null && !Objects.equals(projectId, bound.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "开发会话已固定项目，换项目请新建会话");
        }
        Instant targetPin =
                pinned
                        ? (session.getPinnedAt() == null ? Instant.now() : session.getPinnedAt())
                        : null;
        if (Objects.equals(projectId, session.getProjectId())
                && Objects.equals(targetPin, session.getPinnedAt())) {
            return session;
        }
        if (!sessions.updateOrganization(sessionId, projectId, targetPin, expectedVersion)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Conversation session version is stale");
        }
        return sessions.findByUuid(sessionId);
    }
}
