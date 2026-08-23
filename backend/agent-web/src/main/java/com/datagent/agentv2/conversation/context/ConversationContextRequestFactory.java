package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 从已持久化 invocation 边界、当前工具集和服务配置建立策略请求。 */
@Component
@RequiredArgsConstructor
public class ConversationContextRequestFactory {

    private static final String ERROR_EXECUTION_NOT_FOUND = "Agent execution was not found";

    private final AgentExecutionService executions;
    private final ContextModelBudgetProvider budgets;
    private final ContextModelMessageMapper mappings;

    public ConversationContextRequest create(
            LocalAgentExecution run,
            ModelInvocationCoordinator.ModelInvocationBoundary boundary,
            List<ModelToolDefinition> tools) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }

        // 当前 provider 没有独立 system 消息；该列表保留真实调用边界，未来出现时可原样传入。
        List<ContextMessage> fixedSystemMessages = List.of();
        List<ContextToolDefinition> contextTools = mappings.toContextToolDefinitions(tools);
        return ConversationContextRequest.composeForInvocation(
                new ContextExecutionRef(
                        run.tenantId(),
                        execution.getConversationSessionId(),
                        run.executionId(),
                        boundary.inputUpToConversationMessageSequence()),
                budgets.currentBudget(),
                fixedSystemMessages,
                contextTools);
    }
}
