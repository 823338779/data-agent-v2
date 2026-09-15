package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.config.GitApplicationProperties;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitChangeCardPublisher;
import com.datagent.agentv2.persistence.entity.git.*;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;

/** 冻结、完整性和审阅查询；只有登记完成的候选能够进入用户确认入口。 */
@RequiredArgsConstructor
public final class GitChangeService {
    private final GitWorkspaceService workspaces;
    private final GitChangePersistenceService records;
    private final GitSnapshotStore snapshots;
    private final GitRepositoryClient git;
    private final GitApplicationProperties application;
    private final GitChangeCardPublisher publisher;

    public GitChangeView prepare(
            String sessionId,
            String executionId,
            String requestKey,
            String summary,
            String message,
            long expectedRevision) {
        if (requestKey == null
                || requestKey.isBlank()
                || summary == null
                || summary.isBlank()
                || message == null
                || message.isBlank()
                || message.length() > 10000
                || summary.length() > 10000)
            throw new GitException("GIT_INVALID_REQUEST", "候选请求不完整或超限");
        return workspaces.locked(
                sessionId,
                () -> {
                    var w = workspaces.require(sessionId);
                    String requestDigest =
                            GitSnapshotStore.sha256(
                                    (executionId.length()
                                                    + ":"
                                                    + executionId
                                                    + summary.length()
                                                    + ":"
                                                    + summary
                                                    + message.length()
                                                    + ":"
                                                    + message
                                                    + ":"
                                                    + expectedRevision)
                                            .getBytes(StandardCharsets.UTF_8));
                    var existing = records.findRequest(w.getWorkspaceId(), requestKey);
                    if (existing != null) {
                        if (!requestDigest.equals(existing.getRequestDigest()))
                            throw new GitException("GIT_CONFLICT", "准备请求幂等键已用于不同内容");
                        return GitChangeView.composeFromChange(existing);
                    }
                    workspaces.assertEditable(w);
                    if (w.getDraftRevision() != expectedRevision)
                        throw new GitException("GIT_CONFLICT", "草稿修订号已变化");
                    GitSnapshot snapshot = git.freeze(workspaces.path(w), message);
                    var c = new GitChangeEntity();
                    c.setChangeId("gc_" + UUID.randomUUID());
                    c.setWorkspaceId(w.getWorkspaceId());
                    c.setConversationSessionId(sessionId);
                    c.setRepositoryId(application.repositoryId());
                    c.setBranch("main");
                    c.setBaseCommit(w.getBaseCommit());
                    c.setTreeId(snapshot.treeId());
                    c.setCandidateDigest(snapshot.candidateDigest());
                    c.setSnapshotStorageKey("snapshots/" + c.getChangeId() + ".snapshot");
                    c.setSummary(summary);
                    c.setCommitMessage(message);
                    c.setDraftRevision(expectedRevision);
                    c.setFileCount(snapshot.changes().size());
                    c.setStatus("PENDING_CONFIRMATION");
                    c.setAuthorName(application.authorName());
                    c.setAuthorEmail(application.authorEmail());
                    c.setPrepareRequestKey(requestKey);
                    c.setRequestDigest(requestDigest);
                    c.setVersion(0L);
                    git.retainSnapshot(workspaces.path(w), c.getChangeId(), c.getTreeId());
                    snapshots.save(c, snapshot, git, workspaces.path(w));
                    publisher.publish(executionId, c);
                    var registered = records.find(c.getChangeId());
                    if (registered == null || registered.getConversationMessageId() == null)
                        throw new GitException("GIT_PUBLICATION_FAILED", "候选与卡片尚未完成登记");
                    return GitChangeView.composeFromChange(registered);
                });
    }

    public GitChangeView detail(String sessionId, String changeId) {
        return GitChangeView.composeFromChange(require(sessionId, changeId));
    }

    public List<GitSnapshotStore.SnapshotFile> files(String sessionId, String changeId) {
        return snapshots.load(require(sessionId, changeId)).files().stream()
                .map(f -> new GitSnapshotStore.SnapshotFile(f.fileId(), f.status(), f.path(), null))
                .toList();
    }

    public String diff(String sessionId, String changeId, String fileId) {
        return snapshots.load(require(sessionId, changeId)).files().stream()
                .filter(f -> f.fileId().equals(fileId))
                .findFirst()
                .orElseThrow(() -> new GitException("GIT_FILE_NOT_FOUND", "候选文件不存在"))
                .diff();
    }

    GitChangeEntity require(String sessionId, String changeId) {
        var c = records.find(changeId);
        if (c == null
                || !sessionId.equals(c.getConversationSessionId())
                || !application.repositoryId().equals(c.getRepositoryId()))
            throw new GitException("GIT_CHANGE_NOT_FOUND", "当前会话候选不存在");
        return c;
    }

    void verify(GitChangeEntity c, GitWorkspaceEntity w, boolean matchDraft) {
        snapshots.load(c);
        git.verifyTree(workspaces.path(w), c.getTreeId());
        if (matchDraft) {
            if (!c.getWorkspaceId().equals(w.getWorkspaceId())
                    || !c.getChangeId().equals(w.getActiveChangeId())
                    || !c.getDraftRevision().equals(w.getDraftRevision())
                    || !c.getBaseCommit().equals(w.getBaseCommit()))
                throw new GitException("GIT_CONFLICT", "候选已不对应当前草稿");
            var current = git.freeze(workspaces.path(w), c.getCommitMessage());
            if (!current.treeId().equals(c.getTreeId())
                    || !current.candidateDigest().equals(c.getCandidateDigest()))
                throw new GitException("GIT_SNAPSHOT_CORRUPT", "冻结候选与草稿不一致");
        }
    }
}
