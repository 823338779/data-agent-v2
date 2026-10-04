package com.datagent.agentv2.persistence.service.git;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.git.GitSessionWorkspaceEntity;
import com.datagent.agentv2.persistence.mapper.git.GitSessionWorkspaceMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话可选工作区目录，不删除草稿，不承担人员权限。 */
@Service
@RequiredArgsConstructor
public class GitSessionWorkspaceService
        extends ServiceImpl<GitSessionWorkspaceMapper, GitSessionWorkspaceEntity> {
    private final GitWorkspacePersistenceService workspaces;

    public List<GitSessionWorkspaceEntity> listForSession(String sessionId) {
        return lambdaQuery()
                .eq(GitSessionWorkspaceEntity::getConversationSessionId, sessionId)
                .list();
    }

    public boolean isAttached(String sessionId, String workspaceId) {
        return lambdaQuery()
                .eq(GitSessionWorkspaceEntity::getConversationSessionId, sessionId)
                .eq(GitSessionWorkspaceEntity::getWorkspaceId, workspaceId)
                .exists();
    }

    @Transactional
    public void attach(String sessionId, String workspaceId) {
        // 相同工作区的关联建立短暂串行化，重复请求不会制造重复行。
        if (workspaces.lockByWorkspaceId(workspaceId) == null) {
            throw new IllegalArgumentException("工作区不存在");
        }
        if (!isAttached(sessionId, workspaceId)) {
            var link = new GitSessionWorkspaceEntity();
            link.setConversationSessionId(sessionId);
            link.setWorkspaceId(workspaceId);
            if (!save(link)) {
                throw new IllegalStateException("工作区关联失败");
            }
        }
    }
}
