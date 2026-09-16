package com.datagent.agentv2.persistence.service.git;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.git.GitWorkspaceEntity;
import com.datagent.agentv2.persistence.mapper.git.GitWorkspaceMapper;
import org.springframework.stereotype.Service;

/** 工作区单表查询与 CAS；调用方在文件 I/O 前后分别完成短更新。 */
@Service
public class GitWorkspacePersistenceService
        extends ServiceImpl<GitWorkspaceMapper, GitWorkspaceEntity> {
    public GitWorkspaceEntity find(String session, String repository) {
        return lambdaQuery()
                .eq(GitWorkspaceEntity::getConversationSessionId, session)
                .eq(GitWorkspaceEntity::getRepositoryId, repository)
                .one();
    }

    public GitWorkspaceEntity findByWorkspaceId(String workspaceId) {
        return lambdaQuery().eq(GitWorkspaceEntity::getWorkspaceId, workspaceId).one();
    }

    public boolean replace(GitWorkspaceEntity w) {
        return lambdaUpdate()
                .eq(GitWorkspaceEntity::getWorkspaceId, w.getWorkspaceId())
                .eq(GitWorkspaceEntity::getVersion, w.getVersion())
                .set(GitWorkspaceEntity::getStorageKey, w.getStorageKey())
                .set(GitWorkspaceEntity::getBaseCommit, w.getBaseCommit())
                .set(GitWorkspaceEntity::getDraftRevision, w.getDraftRevision())
                .set(GitWorkspaceEntity::getDraftDigest, w.getDraftDigest())
                .set(GitWorkspaceEntity::getActiveChangeId, w.getActiveChangeId())
                .set(GitWorkspaceEntity::getStatus, w.getStatus())
                .set(GitWorkspaceEntity::getVersion, w.getVersion() + 1)
                .update();
    }
}
