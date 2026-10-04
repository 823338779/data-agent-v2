package com.datagent.agentv2.persistence.service.git;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.git.GitExecutionWorkspaceEntity;
import com.datagent.agentv2.persistence.mapper.git.GitExecutionWorkspaceMapper;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在执行受理事务中冻结工作区上下文；任何重放都不能替换目标。 */
@Service
@RequiredArgsConstructor
public class GitExecutionWorkspaceService
        extends ServiceImpl<GitExecutionWorkspaceMapper, GitExecutionWorkspaceEntity> {
    private static final String READY = "READY";
    private static final String MAIN = "refs/heads/main";
    private final GitWorkspacePersistenceService workspaces;
    private final GitSessionWorkspaceService sessions;

    public GitExecutionWorkspaceEntity findByExecutionId(String executionId) {
        return lambdaQuery().eq(GitExecutionWorkspaceEntity::getExecutionId, executionId).one();
    }

    @Transactional
    public GitExecutionWorkspaceEntity bind(
            String executionId, String sessionId, String workspaceId) {
        // 已受理执行先重放原目标，不重新依赖后来改变的关联或草稿状态。
        var prior = findByExecutionId(executionId);
        if (prior != null) {
            if (!Objects.equals(prior.getConversationSessionId(), sessionId)
                    || !Objects.equals(prior.getWorkspaceId(), workspaceId)) {
                throw new IllegalStateException("执行不能重新绑定到其他工作区");
            }
            return prior;
        }
        var workspace = workspaces.lockByWorkspaceId(workspaceId);
        if (workspace == null || !sessions.isAttached(sessionId, workspaceId)) {
            throw new IllegalArgumentException("当前会话未关联此工作区");
        }
        if (!READY.equals(workspace.getStatus())
                || Boolean.TRUE.equals(workspace.getLegacyReadOnly())
                || workspace.getProjectId() == null
                || workspace.getBranchId() == null
                || workspace.getGitRef() == null
                || MAIN.equals(workspace.getGitRef())) {
            throw new IllegalStateException("工作区暂不可用于开发执行");
        }
        // 复制可信持久化目标，解析工具时只读本记录，不再次读取会话选择。
        var binding = new GitExecutionWorkspaceEntity();
        binding.setExecutionId(executionId);
        binding.setConversationSessionId(sessionId);
        binding.setWorkspaceId(workspaceId);
        binding.setProjectId(workspace.getProjectId());
        binding.setBranchId(workspace.getBranchId());
        binding.setGitRef(workspace.getGitRef());
        if (!save(binding)) {
            throw new IllegalStateException("执行工作区绑定失败");
        }
        return binding;
    }
}
