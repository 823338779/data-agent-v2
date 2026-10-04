package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** 在独立目录合并固定三方输入，成功后通过 CAS 替换草稿目录指针。 */
@RequiredArgsConstructor
public final class GitWorkspaceSyncService {
    private static final String UNCHANGED = "UNCHANGED";
    private static final String MERGED = "MERGED";
    private static final String CONFLICT = "CONFLICT";
    private static final String EMPTY_CHANGE = "GIT_EMPTY_CHANGE";
    private static final String STORAGE_PREFIX = "workspaces/";
    private final GitWorkspaceService workspaces;
    private final GitRepositoryClient git;

    public GitSyncResult sync(String workspaceId, long expectedRevision) {
        return merge(workspaceId, expectedRevision, null, null);
    }

    /** 人工决议必须基于相同草稿修订及远端 SHA，完整覆盖当前冲突路径。 */
    public GitSyncResult resolve(
            String workspaceId,
            long expectedRevision,
            String remoteCommitSha,
            Map<String, String> resolutions) {
        if (remoteCommitSha == null || resolutions == null || resolutions.isEmpty())
            throw new GitException("GIT_CONFLICT", "缺少固定远端或人工决议");
        return merge(workspaceId, expectedRevision, remoteCommitSha, resolutions);
    }

    private GitSyncResult merge(
            String workspaceId,
            long expectedRevision,
            String expectedRemote,
            Map<String, String> resolutions) {
        return workspaces.locked(
                workspaceId,
                () -> {
                    var w = workspaces.require(workspaceId);
                    workspaces.assertEditable(w);
                    workspaces.assertBranchActive(w);
                    if (w.getBranchId() == null || w.getDraftRevision() != expectedRevision) {
                        throw new GitException("GIT_CONFLICT", "工作区目标或草稿修订号已变化");
                    }
                    String remote = git.fetchBranch(workspaces.path(w), w.getGitRef());
                    if (expectedRemote != null && !expectedRemote.equals(remote))
                        throw new GitException("GIT_CONFLICT", "远端已变化，请重新同步读取冲突材料");
                    if (remote.equals(w.getBaseCommit())) {
                        return new GitSyncResult(
                                workspaceId,
                                w.getBaseCommit(),
                                remote,
                                expectedRevision,
                                UNCHANGED,
                                List.of());
                    }
                    String tree;
                    try {
                        tree = git.freeze(workspaces.path(w)).treeId();
                    } catch (GitException failure) {
                        if (!EMPTY_CHANGE.equals(failure.code())) throw failure;
                        tree = w.getBaseCommit();
                    }
                    // 每次基于固定输入重建合并树；客户端不能注入任意 tree 或目录。
                    var merged =
                            git.mergeDraft(workspaces.path(w), w.getBaseCommit(), tree, remote);
                    if (resolutions != null
                            && !new HashSet<>(merged.conflictPaths()).equals(resolutions.keySet()))
                        throw new GitException("GIT_CONFLICT", "人工决议必须完整对应冲突路径");
                    if (!merged.conflictPaths().isEmpty() && resolutions == null) {
                        final String localTree = tree;
                        var conflicts =
                                merged.conflictPaths().stream()
                                        .map(
                                                name ->
                                                        new GitConflict(
                                                                name,
                                                                git.readConflictFile(
                                                                        workspaces.path(w),
                                                                        w.getBaseCommit(),
                                                                        name),
                                                                git.readConflictFile(
                                                                        workspaces.path(w),
                                                                        localTree,
                                                                        name),
                                                                git.readConflictFile(
                                                                        workspaces.path(w),
                                                                        remote,
                                                                        name)))
                                        .toList();
                        workspaces.checkConflictSize(conflicts);
                        return new GitSyncResult(
                                workspaceId,
                                w.getBaseCommit(),
                                remote,
                                expectedRevision,
                                CONFLICT,
                                merged.conflictPaths(),
                                conflicts);
                    }
                    String storageKey = STORAGE_PREFIX + UUID.randomUUID();
                    var source = workspaces.path(w);
                    var destination =
                            source.getParent()
                                    .resolve(storageKey.substring(STORAGE_PREFIX.length()));
                    git.materializeMerge(source, destination, merged.tree(), remote);
                    if (resolutions != null) workspaces.resolveFiles(destination, resolutions);
                    workspaces.acceptSync(w, storageKey, remote);
                    return new GitSyncResult(
                            workspaceId, remote, remote, expectedRevision + 1, MERGED, List.of());
                });
    }
}
