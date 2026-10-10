package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.persistence.entity.development.DevelopmentProjectEntity;
import com.datagent.agentv2.persistence.entity.development.DevelopmentSessionEntity;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 只管理 Agent 开发上下文；不依赖预览、交付或恢复服务。 */
@Service
@RequiredArgsConstructor
public class DevelopmentContextService {
    private static final String NOT_FOUND = "DEVELOPMENT_NOT_FOUND";
    private static final String CONFLICT = "DEVELOPMENT_CONFLICT";
    private static final String UNINITIALIZED = "UNINITIALIZED";
    private final DevelopmentContextPersistenceService records;
    private final ConversationProjectService projects;
    private final ConversationSessionService sessions;
    private final ObjectMapper json;

    @Transactional
    public DevelopmentConfig configure(
            String projectId, DevelopmentConfig request, long expectedVersion) {
        if (projects.lockByProjectId(projectId) == null)
            throw new DevelopmentException(NOT_FOUND, "项目不存在", 404);
        var row = new DevelopmentProjectEntity();
        row.setProjectId(projectId);
        row.setRepository(request.repository());
        row.setConfigJson(json.writeValueAsString(request));
        row.setVersion(expectedVersion);
        row.setEnabled(true);
        try {
            return request.atVersion(records.configure(row, expectedVersion).getVersion());
        } catch (IllegalStateException conflict) {
            throw new DevelopmentException(CONFLICT, conflict.getMessage(), 409);
        }
    }

    public DevelopmentConfig config(String projectId) {
        var row = records.project(projectId);
        if (row == null) throw new DevelopmentException(NOT_FOUND, "项目未配置开发能力", 404);
        return json.readValue(row.getConfigJson(), DevelopmentConfig.class)
                .atVersion(row.getVersion());
    }

    /** Git 基线由调用方在事务外读取；这里在项目/会话锁下验证归属并冻结目标。 */
    @Transactional
    public DevelopmentContext bind(
            String sessionId,
            String projectId,
            String branchName,
            String baselineSha,
            DevelopmentConfig observedConfig) {
        if (projects.lockByProjectId(projectId) == null)
            throw new DevelopmentException(NOT_FOUND, "项目不存在", 404);
        var session = sessions.lockById(sessionId);
        if (session == null || !Objects.equals(projectId, session.getProjectId())) {
            throw new DevelopmentException(CONFLICT, "会话不属于该开发项目", 409);
        }
        DevelopmentConfig config = config(projectId);
        // 仓库和环境配置在Git读取期间可被更新；此时整个会话创建事务必须回滚。
        if (!config.equals(observedConfig))
            throw new DevelopmentException(CONFLICT, "读取分支期间项目配置已变化，请重新创建会话", 409);
        var row = new DevelopmentSessionEntity();
        row.setSessionId(sessionId);
        row.setProjectId(projectId);
        row.setRepository(config.repository());
        row.setBranchName(branchName);
        row.setBaselineSha(baselineSha);
        row.setConfigSnapshot(json.writeValueAsString(config));
        row.setStorageKey(UUID.randomUUID().toString());
        row.setRevision(0L);
        row.setVersion(0L);
        row.setLocalState(UNINITIALIZED);
        try {
            return context(records.bind(row));
        } catch (IllegalStateException conflict) {
            throw new DevelopmentException(CONFLICT, conflict.getMessage(), 409);
        }
    }

    public DevelopmentContext get(String sessionId) {
        var row = records.session(sessionId);
        if (row == null) throw new DevelopmentException(NOT_FOUND, "会话尚未绑定开发目标", 404);
        return context(row);
    }

    public DevelopmentContext forNewOperation(String sessionId) {
        var context = get(sessionId);
        var project = records.project(context.projectId());
        if (project == null
                || !Boolean.TRUE.equals(project.getEnabled())
                || project.getDiagnostic() != null) {
            throw new DevelopmentException(CONFLICT, "项目已停用或迁移来源待核实", 409);
        }
        // 操作固定当前配置；已受理操作只读自身 contextJson，不回到这里解析新版本。
        return new DevelopmentContext(
                sessionId,
                context.projectId(),
                context.repository(),
                context.branchName(),
                context.baselineSha(),
                config(context.projectId()));
    }

    private DevelopmentContext context(DevelopmentSessionEntity row) {
        return new DevelopmentContext(
                row.getSessionId(),
                row.getProjectId(),
                row.getRepository(),
                row.getBranchName(),
                row.getBaselineSha(),
                json.readValue(row.getConfigSnapshot(), DevelopmentConfig.class));
    }
}
