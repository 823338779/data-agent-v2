package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.Environment;
import com.datagent.agentv2.development.model.DevelopmentException;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 开发预览的唯一用例：同一持久操作先保存，随后构建并等待该构建的部署意图。 */
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("${data-agent.git.enabled:false} and ${data-agent.dbt.enabled:false}")
public class PreviewBuildService {
    private static final String DIRTY = "DIRTY", CONFLICT = "DEVELOPMENT_CONFLICT";
    private final DevelopmentOperations operations;
    private final DevelopmentWorkspaceService workspaces;
    private final SourceSaveService saves;
    private final DevelopmentBuildService builds;

    public OperationView start(String session, PreviewRequest request) {
        return workspaces.locked(
                session,
                () -> {
                    var prior =
                            operations.existing(
                                    session,
                                    OperationType.PREVIEW,
                                    request.idempotencyKey(),
                                    request);
                    if (prior != null) return operations.view(prior);
                    var workspace = workspaces.ready(session);
                    workspaces.checkRevision(workspace, request.expectedRevision());
                    if (DIRTY.equals(workspace.getLocalState())
                            && (request.commitMessage() == null
                                    || request.commitMessage().isBlank())) {
                        throw new DevelopmentException(CONFLICT, "保存本地修改需要提交说明", 409);
                    }
                    return operations.view(
                            operations.accept(
                                    session,
                                    OperationType.PREVIEW,
                                    request.idempotencyKey(),
                                    request,
                                    Phase.SAVING));
                });
    }

    public OperationView reconcile(UUID id) {
        var row = operations.require(id);
        return workspaces.locked(
                row.getSessionId(),
                () -> {
                    var current = operations.require(id);
                    if (!DevelopmentOperations.active(current)) return operations.view(current);
                    if (current.getPushedSha() == null) {
                        var saved = saves.reconcile(id);
                        if (saved.pushedSha() == null || saved.status() != OperationStatus.RUNNING)
                            return saved;
                    }
                    return builds.reconcile(operations.require(id), Environment.PREVIEW);
                });
    }
}
