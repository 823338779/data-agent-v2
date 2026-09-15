package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.entity.git.*;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.platform.context.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;

/** 卡片按钮唯一授权入口；先持久化 CAS 再排队，后台固定仓库串行且不跨 Git 持有事务。 */
@RequiredArgsConstructor
public final class GitSubmissionService {
    private final GitWorkspaceService workspaces;
    private final GitChangeService changes;
    private final GitChangePersistenceService records;
    private final GitRepositoryClient git;
    private final Executor executor;
    private final Clock clock;

    public GitChangeView confirm(String session, String id, long expectedVersion) {
        return workspaces.locked(
                session,
                () -> {
                    var c = changes.require(session, id);
                    if (Objects.equals(c.getConfirmationVersion(), expectedVersion)) return view(c);
                    expect(c, expectedVersion, "PENDING_CONFIRMATION");
                    verifyForSubmission(c);
                    if (!records.confirm(
                            c,
                            OperatorContext.requiredUserId(),
                            clock.instant().truncatedTo(ChronoUnit.SECONDS))) throw conflict();
                    schedule(session, id);
                    return changes.detail(session, id);
                });
    }

    public GitChangeView cancel(String session, String id, long expectedVersion) {
        return workspaces.locked(
                session,
                () -> {
                    var c = changes.require(session, id);
                    if (Objects.equals(c.getCancelVersion(), expectedVersion)) return view(c);
                    expect(c, expectedVersion, "PENDING_CONFIRMATION");
                    if (!records.button(c, false)) throw conflict();
                    return changes.detail(session, id);
                });
    }

    public GitChangeView retry(String session, String id, long expectedVersion) {
        return workspaces.locked(
                session,
                () -> {
                    var c = changes.require(session, id);
                    if (Objects.equals(c.getRetryVersion(), expectedVersion)) return view(c);
                    expect(c, expectedVersion, "FAILED");
                    if (c.getConfirmedAt() == null
                            || c.getConfirmedBy() == null
                            || c.getCommitTime() == null) throw conflict();
                    verifyForSubmission(c);
                    if (!records.button(c, true)) throw conflict();
                    schedule(session, id);
                    return changes.detail(session, id);
                });
    }

    /** 显式核实和启动恢复共用，不生成 commit，不执行 push。 */
    public GitChangeView reconcile(String session, String id) {
        return workspaces.submitting(
                session,
                () ->
                        TenantContext.call(
                                TenantContext.TENANT_ID,
                                () -> {
                                    var c = changes.require(session, id);
                                    if (!List.of("SUBMITTING", "VERIFYING").contains(c.getStatus()))
                                        return view(c);
                                    if (c.getConfirmedAt() == null
                                            || c.getConfirmedBy() == null
                                            || c.getCommitTime() == null) {
                                        transition(c, "FAILED", "GIT_CONFIRMATION_MISSING");
                                        return changes.detail(session, id);
                                    }
                                    if (c.getCommitId() == null) {
                                        transition(c, "FAILED", "GIT_RETRY_REQUIRED");
                                        return changes.detail(session, id);
                                    }
                                    reconcileCommit(c);
                                    return changes.detail(session, id);
                                }));
    }

    public GitChangeView detail(String session, String id) {
        return changes.detail(session, id);
    }

    private void verifyForSubmission(GitChangeEntity c) {
        try {
            changes.verify(c, workspaces.require(c.getConversationSessionId()), true);
        } catch (GitException failure) {
            records.transition(c, "FAILED", failure.code());
            throw failure;
        }
    }

    private void schedule(String session, String id) {
        try {
            executor.execute(
                    () -> TenantContext.run(TenantContext.TENANT_ID, () -> execute(session, id)));
        } catch (RuntimeException failure) {
            var c = changes.require(session, id);
            if ("SUBMITTING".equals(c.getStatus())) transition(c, "FAILED", "GIT_TASK_REJECTED");
        }
    }

    private void execute(String session, String id) {
        workspaces.submitting(
                session,
                () -> {
                    var c = changes.require(session, id);
                    if (!"SUBMITTING".equals(c.getStatus())) return null;
                    if (c.getConfirmedAt() == null
                            || c.getConfirmedBy() == null
                            || c.getCommitTime() == null) {
                        transition(c, "FAILED", "GIT_CONFIRMATION_MISSING");
                        return null;
                    }
                    try {
                        var w = workspaces.require(session);
                        changes.verify(c, w, true);
                        if (c.getCommitId() != null) verifyCommit(c, w);
                        String remote = git.fetchMain(workspaces.path(w));
                        // 已登记 commit 的重试先核实是否已经被远端包含，不能盲目再次推送。
                        if (c.getCommitId() != null
                                && git.isAncestorOfRemoteMain(
                                        workspaces.path(w), c.getCommitId())) {
                            pushed(c, w);
                            return null;
                        }
                        if (!remote.equals(c.getBaseCommit())) {
                            transition(c, "RECONFIRM_REQUIRED", "GIT_BASE_CHANGED");
                            return null;
                        }
                        if (c.getCommitId() == null) {
                            String commit =
                                    git.createCommit(
                                            workspaces.path(w),
                                            c.getTreeId(),
                                            c.getBaseCommit(),
                                            c.getCommitMessage(),
                                            c.getAuthorName(),
                                            c.getAuthorEmail(),
                                            c.getCommitTime());
                            // 只有持久化成功才能向远端发送。登记前退出可用固定元信息重建相同对象。
                            if (!records.recordCommit(c, commit)) throw conflict();
                            c = changes.require(session, id);
                        }
                        // 所有 push 异常均先核实；非零退出也可能发生在远端已经接受之后。
                        try {
                            git.pushMain(workspaces.path(w), c.getCommitId(), c.getBaseCommit());
                            pushed(c, w);
                        } catch (RuntimeException uncertain) {
                            var current = changes.require(session, id);
                            transition(current, "VERIFYING", "GIT_RESULT_UNKNOWN");
                            reconcileCommit(changes.require(session, id));
                        }
                    } catch (GitException failure) {
                        var current = changes.require(session, id);
                        if ("SUBMITTING".equals(current.getStatus()))
                            transition(current, "FAILED", failure.code());
                    }
                    return null;
                });
    }

    private void reconcileCommit(GitChangeEntity c) {
        GitWorkspaceEntity w;
        try {
            w = workspaces.record(c.getConversationSessionId());
            try {
                verifyCommit(c, w);
            } catch (GitException corrupt) {
                transition(c, "FAILED", corrupt.code());
                return;
            }
            // 远端包含关系是 push 成功依据；本地草稿缺失不能被静默重建，但也不能谎报远端未接受。
            String remote = git.fetchMain(workspaces.path(w));
            if (git.isAncestorOfRemoteMain(workspaces.path(w), c.getCommitId())) {
                pushed(c, w);
                return;
            }
            if (!remote.equals(c.getBaseCommit()))
                transition(c, "RECONFIRM_REQUIRED", "GIT_BASE_CHANGED");
            else transition(c, "FAILED", "GIT_RETRY_REQUIRED");
        } catch (RuntimeException unavailable) {
            var current = changes.require(c.getConversationSessionId(), c.getChangeId());
            if (!"PUSHED".equals(current.getStatus()))
                transition(current, "VERIFYING", "GIT_RESULT_UNKNOWN");
        }
    }

    private void pushed(GitChangeEntity c, GitWorkspaceEntity w) {
        // 工作区基线同步失败不改变已经核实的远端事实；标记 BROKEN 后要求显式重建草稿。
        try {
            if (c.getChangeId().equals(w.getActiveChangeId()))
                workspaces.accepted(w, c.getCommitId());
        } catch (RuntimeException failure) {
            workspaces.markBroken(w);
        }
        transition(c, "PUSHED", null);
    }

    private void verifyCommit(GitChangeEntity c, GitWorkspaceEntity w) {
        git.verifyCommit(
                workspaces.path(w),
                c.getCommitId(),
                c.getTreeId(),
                c.getBaseCommit(),
                c.getCommitMessage(),
                c.getAuthorName(),
                c.getAuthorEmail(),
                c.getCommitTime());
    }

    private void transition(GitChangeEntity c, String next, String error) {
        if (!records.transition(c, next, error)) throw conflict();
    }

    private static void expect(GitChangeEntity c, long version, String status) {
        if (c.getVersion() != version || !status.equals(c.getStatus())) throw conflict();
    }

    private static GitChangeView view(GitChangeEntity c) {
        return GitChangeView.composeFromChange(c);
    }

    private static GitException conflict() {
        return new GitException("GIT_CONFLICT", "候选状态或版本已变化");
    }
}
