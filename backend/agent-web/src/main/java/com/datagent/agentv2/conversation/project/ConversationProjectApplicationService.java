package com.datagent.agentv2.conversation.project;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 项目生命周期的事务边界，删除只解除会话关联。 */
@Service
@RequiredArgsConstructor
public class ConversationProjectApplicationService {
    private final ConversationProjectService projects;
    private final ConversationSessionService sessions;
    private final IdGenerator ids;
    private final com.datagent.agentv2.persistence.service.development
                    .DevelopmentContextPersistenceService
            development;

    @Transactional
    public ConversationProject create(String name) {
        try {
            return projects.insertProject(ConversationProject.create(ids.next("project"), name));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @Transactional
    public ConversationProject rename(String projectId, String name, long expectedVersion) {
        var project = lockExpected(projectId, expectedVersion);
        ConversationProject renamed;
        try {
            renamed = project.renamed(name);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        if (project.name().equals(renamed.name())) {
            return project;
        }
        if (!projects.renameProject(projectId, renamed.name(), expectedVersion)) {
            throw conflict();
        }
        return projects.lockByProjectId(projectId);
    }

    @Transactional
    public void delete(String projectId, long expectedVersion) {
        lockExpected(projectId, expectedVersion);
        development.disable(projectId);
        // 在项目锁内先清关联再逻辑删除；后续失败会回滚两步，置顶和运行状态始终保留。
        sessions.detachProject(projectId);
        if (!projects.deleteProject(projectId, expectedVersion)) {
            throw conflict();
        }
    }

    private ConversationProject lockExpected(String projectId, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project version");
        }
        var project = projects.lockByProjectId(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        if (project.version() != expectedVersion) {
            throw conflict();
        }
        return project;
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Project version is stale");
    }
}
