package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.config.GitApplicationProperties;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitCandidateValidation;
import com.datagent.agentv2.git.port.GitChangeCardPublisher;
import com.datagent.agentv2.persistence.entity.git.*;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;

/** 冻结、完整性和审阅查询；只有登记完成的候选能够进入用户确认入口。 */
@RequiredArgsConstructor
public final class GitChangeService {
    private static final String MAIN = "main";
    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String CONTEXT_VERSION = "branch-candidate-v1";
    private final GitWorkspaceService workspaces;
    private final GitChangePersistenceService records;
    private final GitSnapshotStore snapshots;
    private final GitRepositoryClient git;
    private final GitApplicationProperties application;
    private final GitCandidateValidation validation;
    private final GitChangeCardPublisher publisher;

    public GitChangeView prepare(
            String sessionId,
            String executionId,
            String requestKey,
            String summary,
            String message,
            long expectedRevision) {
        return prepareWorkspace(
                sessionId, sessionId, executionId, requestKey, summary, message, expectedRevision);
    }

    /** 工作区来自执行绑定，会话仅决定候选卡片归属。 */
    public GitChangeView prepareWorkspace(
            String workspaceId,
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
                || summary.length() > 10000) {
            throw new GitException("GIT_INVALID_REQUEST", "候选请求不完整或超限");
        }
        var prepared =
                workspaces.locked(
                        workspaceId,
                        () -> {
                            var w = workspaces.require(workspaceId);
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
                                if (!requestDigest.equals(existing.getRequestDigest())) {
                                    throw new GitException("GIT_CONFLICT", "准备请求幂等键已用于不同内容");
                                }
                                verifyValidation(existing);
                                return GitChangeView.composeFromChange(existing);
                            }
                            workspaces.assertEditable(w);
                            workspaces.assertBranchActive(w);
                            if (w.getDraftRevision() != expectedRevision) {
                                throw new GitException("GIT_CONFLICT", "草稿修订号已变化");
                            }
                            GitSnapshot snapshot = git.freeze(workspaces.path(w), message);
                            var evidence =
                                    validation.validate(
                                            sessionId,
                                            workspaces
                                                    .draftSnapshot(w, snapshot)
                                                    .forSession(sessionId));
                            var c = new GitChangeEntity();
                            c.setValidationEvidence(evidence == null ? null : evidence.encode());
                            c.setChangeId("gc_" + UUID.randomUUID());
                            c.setWorkspaceId(w.getWorkspaceId());
                            c.setConversationSessionId(sessionId);
                            c.setRepositoryId(application.repositoryId());
                            c.setBranch(
                                    w.getGitRef() == null
                                            ? MAIN
                                            : w.getGitRef().substring(HEADS_PREFIX.length()));
                            c.setProjectId(w.getProjectId());
                            c.setBranchId(w.getBranchId());
                            c.setGitRef(w.getGitRef());
                            c.setBaseCommit(w.getBaseCommit());
                            c.setTreeId(snapshot.treeId());
                            c.setCandidateDigest(candidateDigest(w, snapshot));
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
                            if (registered == null
                                    || registered.getConversationMessageId() == null) {
                                throw new GitException("GIT_PUBLICATION_FAILED", "候选与卡片尚未完成登记");
                            }
                            return GitChangeView.composeFromChange(registered);
                        });
        // HTTP 版本核对放在草稿锁外；期间发生的新编辑由后续确认的 revision/CAS 再次阻断。
        checkCurrent(sessionId, prepared.changeId());
        return prepared;
    }

    /** 准备、确认和后台执行都先核对固定凭据；禁止在工作区锁内调用。 */
    void checkCurrent(String sessionId, String changeId) {
        var c = require(sessionId, changeId);
        try {
            validation.checkCurrent(GitValidationEvidence.decode(c.getValidationEvidence()));
        } catch (GitException stale) {
            if ("PENDING_CONFIRMATION".equals(c.getStatus()))
                records.transition(c, "INVALIDATED", stale.code());
            throw stale;
        }
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
                || !application.repositoryId().equals(c.getRepositoryId())) {
            throw new GitException("GIT_CHANGE_NOT_FOUND", "当前会话候选不存在");
        }
        return c;
    }

    void verify(GitChangeEntity c, GitWorkspaceEntity w, boolean matchDraft) {
        snapshots.load(c);
        verifyValidation(c);
        git.verifyTree(workspaces.path(w), c.getTreeId());
        if (matchDraft) {
            if (!c.getWorkspaceId().equals(w.getWorkspaceId())
                    || !Objects.equals(c.getBranchId(), w.getBranchId())
                    || (c.getGitRef() != null && !Objects.equals(c.getGitRef(), w.getGitRef()))
                    || !c.getChangeId().equals(w.getActiveChangeId())
                    || !c.getDraftRevision().equals(w.getDraftRevision())
                    || !c.getBaseCommit().equals(w.getBaseCommit())) {
                throw new GitException("GIT_CONFLICT", "候选已不对应当前草稿");
            }
            var current = git.freeze(workspaces.path(w), c.getCommitMessage());
            if (!current.treeId().equals(c.getTreeId())
                    || !candidateDigest(w, current).equals(c.getCandidateDigest())) {
                throw new GitException("GIT_SNAPSHOT_CORRUPT", "冻结候选与草稿不一致");
            }
        }
    }

    /** 读取冻结快照范围和原基线；重启、重试及确认均不信任模型提供的验证身份。 */
    private void verifyValidation(GitChangeEntity c) {
        var stored = snapshots.load(c);
        var w = workspaces.require(workspaces.target(c));
        var files =
                stored.files().stream()
                        .map(
                                file ->
                                        new GitDraftSnapshot.File(
                                                file.status(),
                                                file.path(),
                                                file.status().equals("A")
                                                        ? null
                                                        : git.readTreeFile(
                                                                workspaces.path(w),
                                                                c.getBaseCommit(),
                                                                file.path()),
                                                file.status().equals("D")
                                                        ? null
                                                        : git.readTreeFile(
                                                                workspaces.path(w),
                                                                c.getTreeId(),
                                                                file.path())))
                        .toList();
        var draft =
                new GitDraftSnapshot(
                        c.getConversationSessionId(),
                        c.getWorkspaceId(),
                        c.getBaseCommit(),
                        c.getTreeId(),
                        c.getDraftRevision(),
                        files,
                        c.getProjectId(),
                        c.getBranchId(),
                        c.getGitRef());
        var expected = validation.validate(c.getConversationSessionId(), draft);
        if (!Objects.equals(expected, GitValidationEvidence.decode(c.getValidationEvidence()))) {
            throw new GitException("DBT_VALIDATION_MISMATCH", "候选校验证据与固定快照不一致");
        }
    }

    /** 保留旧候选摘要；新候选以长度编码绑定项目、工作区、分支实例及完整引用。 */
    private static String candidateDigest(GitWorkspaceEntity w, GitSnapshot snapshot) {
        if (w.getBranchId() == null) return snapshot.candidateDigest();
        String material =
                List.of(
                                CONTEXT_VERSION,
                                w.getProjectId(),
                                w.getWorkspaceId(),
                                w.getBranchId(),
                                w.getGitRef(),
                                snapshot.candidateDigest())
                        .stream()
                        .map(value -> value.length() + ":" + value)
                        .collect(java.util.stream.Collectors.joining());
        return GitSnapshotStore.sha256(material.getBytes(StandardCharsets.UTF_8));
    }
}
