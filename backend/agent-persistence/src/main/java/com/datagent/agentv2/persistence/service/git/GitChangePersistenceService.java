package com.datagent.agentv2.persistence.service.git;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;
import com.datagent.agentv2.persistence.mapper.git.GitChangeMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 候选索引和状态 CAS；事务只覆盖数据库，禁止在事务中执行 Git 或文件操作。 */
@Service
@RequiredArgsConstructor
public class GitChangePersistenceService extends ServiceImpl<GitChangeMapper, GitChangeEntity> {
    private final GitWorkspacePersistenceService workspaces;

    public GitChangeEntity find(String id) {
        return lambdaQuery().eq(GitChangeEntity::getChangeId, id).one();
    }

    public GitChangeEntity findRequest(String workspace, String request) {
        return lambdaQuery()
                .eq(GitChangeEntity::getWorkspaceId, workspace)
                .eq(GitChangeEntity::getPrepareRequestKey, request)
                .one();
    }

    public List<GitChangeEntity> unfinished() {
        return lambdaQuery()
                .in(GitChangeEntity::getStatus, List.of("SUBMITTING", "VERIFYING"))
                .list();
    }

    /** 卡片发布 port 在外层短事务内调用，使候选、工作区活动指针和消息卡片原子登记。 */
    @Transactional
    public void register(GitChangeEntity change) {
        var w = workspaces.findByWorkspaceId(change.getWorkspaceId());
        if (w == null
                || !"READY".equals(w.getStatus())
                || !w.getDraftRevision().equals(change.getDraftRevision())) {
            throw new IllegalStateException("草稿版本冲突");
        }
        if (w.getActiveChangeId() != null) {
            var old = find(w.getActiveChangeId());
            if (old != null && List.of("SUBMITTING", "VERIFYING").contains(old.getStatus()))
                throw new IllegalStateException("工作区正在提交");
        }
        invalidatePending(w.getWorkspaceId());
        if (!save(change)) throw new IllegalStateException("候选登记失败");
        w.setActiveChangeId(change.getChangeId());
        if (!workspaces.replace(w)) throw new IllegalStateException("工作区版本冲突");
    }

    public void invalidatePending(String workspace) {
        // 只关闭未授权入口；已确认任务必须由提交/核实状态机收敛。
        var pending =
                lambdaQuery()
                        .eq(GitChangeEntity::getWorkspaceId, workspace)
                        .eq(GitChangeEntity::getStatus, "PENDING_CONFIRMATION")
                        .list();
        for (var candidate : pending) {
            if (!transition(candidate, "INVALIDATED", null))
                throw new IllegalStateException("候选版本冲突");
        }
    }

    public boolean transition(GitChangeEntity c, String next, String error) {
        return lambdaUpdate()
                .eq(GitChangeEntity::getChangeId, c.getChangeId())
                .eq(GitChangeEntity::getVersion, c.getVersion())
                .eq(GitChangeEntity::getStatus, c.getStatus())
                .set(GitChangeEntity::getStatus, next)
                .set(GitChangeEntity::getErrorCode, error)
                .set(GitChangeEntity::getVersion, c.getVersion() + 1)
                .update();
    }

    public boolean confirm(GitChangeEntity c, String operator, Instant time) {
        return lambdaUpdate()
                .eq(GitChangeEntity::getChangeId, c.getChangeId())
                .eq(GitChangeEntity::getVersion, c.getVersion())
                .eq(GitChangeEntity::getStatus, "PENDING_CONFIRMATION")
                .set(GitChangeEntity::getStatus, "SUBMITTING")
                .set(GitChangeEntity::getConfirmedBy, operator)
                .set(GitChangeEntity::getConfirmationVersion, c.getVersion())
                .set(GitChangeEntity::getConfirmedAt, time)
                .set(GitChangeEntity::getCommitTime, time)
                .set(GitChangeEntity::getVersion, c.getVersion() + 1)
                .update();
    }

    public boolean recordCommit(GitChangeEntity c, String commit) {
        return lambdaUpdate()
                .eq(GitChangeEntity::getChangeId, c.getChangeId())
                .eq(GitChangeEntity::getVersion, c.getVersion())
                .eq(GitChangeEntity::getStatus, "SUBMITTING")
                .isNull(GitChangeEntity::getCommitId)
                .set(GitChangeEntity::getCommitId, commit)
                .set(GitChangeEntity::getVersion, c.getVersion() + 1)
                .update();
    }

    public boolean button(GitChangeEntity c, boolean retry) {
        var update =
                lambdaUpdate()
                        .eq(GitChangeEntity::getChangeId, c.getChangeId())
                        .eq(GitChangeEntity::getVersion, c.getVersion())
                        .eq(GitChangeEntity::getStatus, retry ? "FAILED" : "PENDING_CONFIRMATION")
                        .set(GitChangeEntity::getStatus, retry ? "SUBMITTING" : "CANCELLED")
                        .set(GitChangeEntity::getErrorCode, null)
                        .set(GitChangeEntity::getVersion, c.getVersion() + 1);
        if (retry) update.set(GitChangeEntity::getRetryVersion, c.getVersion());
        else update.set(GitChangeEntity::getCancelVersion, c.getVersion());
        return update.update();
    }
}
