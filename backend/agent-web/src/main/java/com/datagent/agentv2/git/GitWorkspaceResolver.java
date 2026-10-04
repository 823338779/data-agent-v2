package com.datagent.agentv2.git;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.git.model.GitWorkspaceContext;
import com.datagent.agentv2.persistence.service.git.GitExecutionWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 只读取执行受理时的持久化绑定，不能回退到会话或 main。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public final class GitWorkspaceResolver
        implements com.datagent.agentv2.git.port.GitWorkspaceResolver {
    private static final String REQUIRED = "GIT_WORKSPACE_REQUIRED";
    private final GitExecutionWorkspaceService bindings;

    @Override
    public GitWorkspaceContext resolve(InvocationContext context) {
        if (context == null || context.executionId() == null || context.executionId().isBlank()) {
            throw new GitException(REQUIRED, "缺少可信执行上下文");
        }
        var binding = bindings.findByExecutionId(context.executionId());
        if (binding == null) {
            throw new GitException(REQUIRED, "请先选择工作区，再发送开发指令");
        }
        return new GitWorkspaceContext(
                binding.getWorkspaceId(),
                binding.getProjectId(),
                binding.getBranchId(),
                binding.getGitRef());
    }
}
