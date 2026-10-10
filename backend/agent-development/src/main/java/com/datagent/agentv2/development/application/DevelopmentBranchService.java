package com.datagent.agentv2.development.application;

import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentException;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实Git分支枚举和显式创建；读取及恢复不会重建被删除的分支。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
public class DevelopmentBranchService {
    private static final String MAIN = "main",
            PREFIX = "project:",
            CONFLICT = "DEVELOPMENT_CONFLICT";
    private final DevelopmentContextService contexts;
    private final DevelopmentContextPersistenceService records;
    private final DevelopmentOperations operations;
    private final GitRepositoryClient git;

    public List<BranchInfo> list(String project) {
        return git.listBranches(contexts.config(project).repository());
    }

    public OperationView create(String project, BranchRequest request) {
        String session = PREFIX + project;
        var existing =
                operations.existing(
                        session, OperationType.CREATE_BRANCH, request.idempotencyKey(), request);
        if (existing != null) return operations.view(existing);
        var configured = records.project(project);
        if (configured == null
                || !Boolean.TRUE.equals(configured.getEnabled())
                || configured.getDiagnostic() != null)
            throw new DevelopmentException(CONFLICT, "项目不能创建分支", 409);
        if (request.branchName() == null
                || request.branchName().isBlank()
                || MAIN.equals(request.branchName()))
            throw new DevelopmentException(CONFLICT, "需要非main的开发分支名称", 409);
        var config = contexts.config(project);
        var branches = git.listBranches(config.repository());
        if (branches.stream().anyMatch(b -> b.branchName().equals(request.branchName())))
            throw new DevelopmentException(CONFLICT, "分支已存在", 409);
        String base =
                branches.stream()
                        .filter(b -> MAIN.equals(b.branchName()))
                        .findFirst()
                        .orElseThrow(() -> new DevelopmentException(CONFLICT, "main不存在", 409))
                        .headSha();
        var context =
                new DevelopmentContext(
                        session, project, config.repository(), request.branchName(), base, config);
        return operations.view(
                operations.acceptFrozen(
                        context,
                        OperationType.CREATE_BRANCH,
                        request.idempotencyKey(),
                        request,
                        Phase.PREPARING));
    }

    public OperationView reconcile(UUID id) {
        var row = operations.require(id);
        if (!DevelopmentOperations.active(row)) return operations.view(row);
        var context = operations.context(row);
        try {
            if (Phase.PREPARING.name().equals(row.getPhase())) {
                row.setCandidateSha(context.baselineSha());
                row = operations.update(row, Phase.PUSHING, OperationStatus.RUNNING, null);
                git.createBranch(context.repository(), context.branchName(), context.baselineSha());
            }
            var found =
                    git.listBranches(context.repository()).stream()
                            .filter(b -> b.branchName().equals(context.branchName()))
                            .findFirst();
            if (found.isEmpty())
                return operations.view(
                        operations.update(
                                row,
                                Phase.VERIFYING,
                                OperationStatus.BLOCKED,
                                "BRANCH_CREATE_UNVERIFIED"));
            var evidence =
                    git.verifyPush(
                            context.repository(), context.branchName(), context.baselineSha());
            if (!evidence.verified())
                return operations.view(
                        operations.update(row, Phase.VERIFYING, OperationStatus.BLOCKED, CONFLICT));
            row.setPushedSha(context.baselineSha());
            return operations.view(
                    operations.update(row, Phase.PUSHED, OperationStatus.SUCCEEDED, null));
        } catch (GitException failure) {
            return operations.fail(row, OperationStatus.RESULT_UNKNOWN, failure.code());
        }
    }
}
