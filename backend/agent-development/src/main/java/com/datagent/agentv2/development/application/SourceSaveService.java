package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.persistence.entity.development.DevelopmentOperationEntity;
import com.datagent.agentv2.persistence.service.development.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 保存仅证明 Git 接收固定候选；不做 YAML 校验，不发起构建。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class SourceSaveService {
    private static final String HEADS = "refs/heads/", EMPTY = "GIT_EMPTY_CHANGE";
    private static final String AUTHOR = "Data Agent", EMAIL = "data-agent@localhost";
    private static final String CONFLICT = "GIT_MERGE_CONFLICT";
    private final DevelopmentOperations operations;
    private final DevelopmentWorkspaceService workspaces;
    private final DevelopmentContextPersistenceService contexts;
    private final GitRepositoryClient git;

    public OperationView start(String session, SaveRequest request) {
        return workspaces.locked(
                session,
                () -> {
                    var prior =
                            operations.existing(
                                    session, OperationType.SAVE, request.idempotencyKey(), request);
                    if (prior != null) return operations.view(prior);
                    workspaces.checkRevision(workspaces.ready(session), request.expectedRevision());
                    return operations.view(
                            operations.accept(
                                    session,
                                    OperationType.SAVE,
                                    request.idempotencyKey(),
                                    request,
                                    Phase.PREPARING));
                });
    }

    public OperationView reconcile(UUID operationId) {
        var operation = operations.require(operationId);
        return workspaces.locked(operation.getSessionId(), () -> advance(operationId));
    }

    private OperationView advance(UUID id) {
        var row = operations.require(id);
        if (!DevelopmentOperations.active(row)) return operations.view(row);
        try {
            var context = operations.context(row);
            var request = saveRequest(row);
            var client = git.forRepository(context.repository());
            String ref = HEADS + context.branchName();
            // 已有候选必须先检查远端；即使本地目录消失，也可核实已成功但响应丢失的 push。
            if (row.getCandidateSha() != null) {
                var evidence =
                        client.verifyPush(
                                context.repository(), context.branchName(), row.getCandidateSha());
                if (evidence.verified()) {
                    return complete(row);
                }
                if (!evidence.remoteHeadSha().equals(row.getCandidateParentSha())) {
                    throw new DevelopmentException(CONFLICT, "原候选未被核实且远端已变化，需要新保存操作", 409);
                }
            }
            var session = workspaces.ready(context.sessionId());
            workspaces.checkRevision(session, request.expectedRevision());
            var directory = workspaces.path(session);
            if (row.getCandidateSha() == null) {
                row =
                        operations.update(
                                row, Phase.RECONCILING_REMOTE, OperationStatus.RUNNING, null);
                String remote = client.fetchBranch(directory, ref);
                String tree;
                try {
                    tree = client.freeze(directory, request.commitMessage()).treeId();
                } catch (GitException empty) {
                    if (!EMPTY.equals(empty.code())) throw empty;
                    // 无本地变更也要核实远端；保存当前远端完整工程。
                    row.setCandidateSha(remote);
                    row.setCandidateParentSha(remote);
                    row = operations.update(row, Phase.VERIFYING, OperationStatus.RUNNING, null);
                    return complete(row);
                }
                if (!remote.equals(session.getBaselineSha())) {
                    var merged =
                            client.mergeDraft(directory, session.getBaselineSha(), tree, remote);
                    if (!merged.conflictPaths().isEmpty())
                        throw new GitException(CONFLICT, "本地修改与共享分支冲突");
                    tree = merged.tree();
                }
                row.setCandidateParentSha(remote);
                row.setCandidateSha(
                        client.createCommit(
                                directory,
                                tree,
                                remote,
                                request.commitMessage(),
                                AUTHOR,
                                EMAIL,
                                Instant.now()));
                // 候选持久化先于 push；任何重试只能重用这一个 SHA。
                row = operations.update(row, Phase.PUSHING, OperationStatus.RUNNING, null);
            }
            client.pushCommit(directory, ref, row.getCandidateParentSha(), row.getCandidateSha());
            row = operations.update(row, Phase.VERIFYING, OperationStatus.RUNNING, null);
            if (!client.verifyPush(
                            context.repository(), context.branchName(), row.getCandidateSha())
                    .verified()) {
                return operations.view(
                        operations.update(
                                row,
                                Phase.VERIFYING,
                                OperationStatus.RESULT_UNKNOWN,
                                "PUSH_UNVERIFIED"));
            }
            return complete(row);
        } catch (DevelopmentException failure) {
            return operations.fail(row, OperationStatus.BLOCKED, failure.getCode());
        } catch (GitException failure) {
            // push 后网络失败不能当成失败重建候选；后台继续只核实原候选。
            return operations.fail(
                    row,
                    row.getCandidateSha() == null
                            ? OperationStatus.BLOCKED
                            : OperationStatus.RESULT_UNKNOWN,
                    failure.code());
        }
    }

    private OperationView complete(DevelopmentOperationEntity row) {
        var session = contexts.session(row.getSessionId());
        if (!row.getCandidateSha().equals(session.getPushedSha()))
            workspaces.saved(session, row.getCandidateSha());
        row.setPushedSha(row.getCandidateSha());
        boolean preview = OperationType.PREVIEW.name().equals(row.getType());
        return operations.view(
                operations.update(
                        row,
                        preview ? Phase.BUILD_SUBMITTING : Phase.PUSHED,
                        preview ? OperationStatus.RUNNING : OperationStatus.SUCCEEDED,
                        null));
    }

    private static final String PREVIEW_MESSAGE = "Save source for preview";

    private SaveRequest saveRequest(DevelopmentOperationEntity row) {
        if (OperationType.SAVE.name().equals(row.getType()))
            return operations.request(row, SaveRequest.class);
        if (!OperationType.PREVIEW.name().equals(row.getType()))
            throw new IllegalArgumentException("该操作不属于源码保存");
        var preview = operations.request(row, PreviewRequest.class);
        return new SaveRequest(
                preview.idempotencyKey(),
                preview.expectedRevision(),
                preview.commitMessage() == null ? PREVIEW_MESSAGE : preview.commitMessage());
    }
}
