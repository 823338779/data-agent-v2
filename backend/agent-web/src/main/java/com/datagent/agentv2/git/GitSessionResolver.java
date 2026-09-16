package com.datagent.agentv2.git;

import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 从服务端执行记录解析真实会话；模型参数没有指定或替换会话的入口。 */
@Component
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public final class GitSessionResolver implements com.datagent.agentv2.git.port.GitSessionResolver {
    private final AgentExecutionService executions;

    @Override
    public String resolveSessionId(String executionId) {
        if (executionId == null || executionId.isBlank())
            throw new GitException("GIT_EXECUTION_NOT_FOUND", "执行不存在");
        var execution = executions.findByExecutionId(executionId);
        if (execution == null || execution.getConversationSessionId() == null)
            throw new GitException("GIT_EXECUTION_NOT_FOUND", "执行不存在");
        return execution.getConversationSessionId();
    }
}
