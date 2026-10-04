package com.datagent.agentv2.persistence.service.git;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.git.GitWorkspaceEntity;
import com.datagent.agentv2.persistence.mapper.git.GitWorkspaceMapper;
import org.springframework.stereotype.Service;

/** 工作区单表查询与 CAS；调用方在文件 I/O 前后分别完成短更新。 */
@Service
public class GitWorkspacePersistenceService
        extends ServiceImpl<GitWorkspaceMapper, GitWorkspaceEntity> {
    private static final String FOR_UPDATE = "FOR UPDATE";

    public GitWorkspaceEntity find(String session, String repository) {
        return lambdaQuery()
                .eq(GitWorkspaceEntity::getConversationSessionId, session)
                .eq(GitWorkspaceEntity::getRepositoryId, repository)
                .one();
    }

    public GitWorkspaceEntity findByWorkspaceId(String workspaceId) {
        return lambdaQuery().eq(GitWorkspaceEntity::getWorkspaceId, workspaceId).one();
    }

    /** 创建会话内的幂等请求；不能用创建会话替代工作区身份。 */
    public GitWorkspaceEntity findCreationRequest(String sessionId, String creationKey) {
        return lambdaQuery()
                .eq(GitWorkspaceEntity::getConversationSessionId, sessionId)
                .eq(GitWorkspaceEntity::getCreationKey, creationKey)
                .one();
    }

    /** 只供短事务核实身份与状态，锁内不能执行 Git 或文件 I/O。 */
    public GitWorkspaceEntity lockByWorkspaceId(String workspaceId) {
        return lambdaQuery()
                .eq(GitWorkspaceEntity::getWorkspaceId, workspaceId)
                .last(FOR_UPDATE)
                .one();
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
                .update(new GitWorkspaceEntity());
    }
}
