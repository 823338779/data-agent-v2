package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.model.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.util.List;
import org.springframework.stereotype.Component;

/** 从已持久化 invocation 边界、当前工具集和服务配置建立策略请求。 */
@Component
public class ConversationContextRequestFactory {

    private static final String ERROR_EXECUTION_NOT_FOUND = "Agent execution was not found";

    private final AgentExecutionService executions;
    private final ContextModelBudgetProvider budgets;
    public ConversationContextRequestFactory(
            AgentExecutionService executions,
            ContextModelBudgetProvider budgets) {
        this.executions = executions;
        this.budgets = budgets;
    }

    public ConversationContextRequest create(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationCoordinator.ModelInvocationBoundary boundary,
            List<ModelToolDefinition> tools) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }

        // 当前 provider 没有独立 system 消息；该列表保留真实调用边界，未来出现时可原样传入。
        List<ContextMessage> fixedSystemMessages = List.of();
        List<ContextToolDefinition> contextTools =
                tools.stream()
                        .map(
                                tool ->
                                        new ContextToolDefinition(
                                                tool.name(), tool.description(), tool.parameters()))
                        .toList();
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        run.tenantId(),
                        execution.getConversationSessionId(),
                        run.executionId(),
                        run.ownerInstanceId(),
                        run.executionAttemptNumber(),
                        boundary.inputUpToConversationMessageSequence()),
                budgets.currentBudget(),
                fixedSystemMessages,
                contextTools);
    }
}
