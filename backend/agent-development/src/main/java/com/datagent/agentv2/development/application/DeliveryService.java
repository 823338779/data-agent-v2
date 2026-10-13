package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.Environment;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.development.model.DevelopmentOverview.DeliveryScope;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.UUID;

/** 确认完整共享分支后产生一个实际合并M；发布失败只重试固定M的构建。 */
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("${data-agent.git.enabled:false} and ${data-agent.dbt.enabled:false}")
public class DeliveryService {
    private static final String READY = "READY",
            SCOPE = "DELIVERY_SCOPE_CHANGED",
            LOST = "LOCAL_INPUT_LOST";
    private static final String UNCONFIRMED = "MERGE_RESULT_UNKNOWN", GIT = ".git";
    private final DevelopmentOperations operations;
    private final DevelopmentContextService contexts;
    private final DevelopmentWorkspaceService files;
    private final GitRepositoryClient git;
    private final DevelopmentBuildService builds;

    public OperationView start(String session, DeliverRequest request) {
        var prior =
                operations.existing(
                        session, OperationType.DELIVER, request.idempotencyKey(), request);
        if (prior != null) return operations.view(prior);
        return files.locked(
                session,
                () -> {
                    if (!READY.equals(files.ready(session).getLocalState()))
                        throw new DevelopmentException(LOST, "交付前必须先保存本地修改", 409);
                    var context = contexts.forNewOperation(session);
                    confirm(context, request);
                    return operations.view(
                            operations.acceptFrozen(
                                    context,
                                    OperationType.DELIVER,
                                    request.idempotencyKey(),
                                    request,
                                    Phase.PRECHECK));
                });
    }

    public OperationView reconcile(UUID id) {
        var initial = operations.require(id);
        return files.locked(initial.getSessionId(), () -> advance(id));
    }

    private OperationView advance(UUID id) {
        var row = operations.require(id);
        if (!DevelopmentOperations.active(row)) return operations.view(row);
        var context = operations.context(row);
        var request = operations.request(row, DeliverRequest.class);
        try {
            if (row.getMergeSha() == null) {
                var evidenceBefore =
                        row.getCandidateSha() == null
                                ? null
                                : git.verifyMerge(
                                        context.repository(),
                                        request.expectedSourceSha(),
                                        request.expectedMainSha(),
                                        row.getCandidateSha());
                if (evidenceBefore != null
                        && !evidenceBefore.verified()
                        && !evidenceBefore.remoteHeadSha().equals(request.expectedMainSha()))
                    throw new DevelopmentException(SCOPE, "main已推进且原合并无法核实，需要人工核对", 409);
                if (evidenceBefore != null && evidenceBefore.verified()) {
                    row.setMergeSha(row.getCandidateSha());
                    row = operations.update(row, Phase.MERGED, OperationStatus.RUNNING, null);
                } else {
                    MergeCandidate candidate;
                    if (row.getCandidateSha() == null) {
                        if (!READY.equals(files.ready(row.getSessionId()).getLocalState()))
                            throw new DevelopmentException(LOST, "本地输入未保存", 409);
                        confirm(context, request);
                        candidate =
                                git.prepareMerge(
                                        context.repository(),
                                        context.branchName(),
                                        request.expectedSourceSha(),
                                        request.expectedMainSha());
                        row.setCandidateSha(candidate.candidateSha());
                        row.setCandidateStoragePath(candidate.workspace().toString());
                        row = operations.update(row, Phase.MERGING, OperationStatus.RUNNING, null);
                    } else {
                        // 只重推已持久的原候选；本地对象丢失时绝不创建第二个合并。
                        Path path = Path.of(row.getCandidateStoragePath());
                        if (!Files.isDirectory(path.resolve(GIT)))
                            throw new DevelopmentException(LOST, "未确认合并的本地对象已丢失", 409);
                        candidate =
                                new MergeCandidate(
                                        context.repository(),
                                        context.branchName(),
                                        request.expectedSourceSha(),
                                        request.expectedMainSha(),
                                        row.getCandidateSha(),
                                        path);
                    }
                    var evidence = git.pushMerge(candidate);
                    if (!evidence.verified())
                        return operations.view(
                                operations.update(
                                        row,
                                        Phase.MERGING,
                                        OperationStatus.RESULT_UNKNOWN,
                                        UNCONFIRMED));
                    row.setMergeSha(row.getCandidateSha());
                    row = operations.update(row, Phase.MERGED, OperationStatus.RUNNING, null);
                }
            }
            return builds.reconcile(row, Environment.PRODUCTION);
        } catch (DevelopmentException rejected) {
            return operations.fail(row, OperationStatus.BLOCKED, rejected.getCode());
        } catch (GitException unknown) {
            return operations.fail(
                    row,
                    row.getCandidateSha() == null
                            ? OperationStatus.BLOCKED
                            : OperationStatus.RESULT_UNKNOWN,
                    unknown.code());
        }
    }

    private void confirm(DevelopmentContext context, DeliverRequest request) {
        var actual =
                DeliveryScope.compose(
                        context, git.describeBranch(context.repository(), context.branchName()));
        if (!actual.expectedSourceSha().equals(request.expectedSourceSha())
                || !actual.expectedMainSha().equals(request.expectedMainSha())
                || !actual.confirmedScopeDigest().equals(request.confirmedScopeDigest())) {
            throw new DevelopmentException(SCOPE, "共享分支、main或配置已经变化，需要重新确认完整交付范围", 409);
        }
    }
}
