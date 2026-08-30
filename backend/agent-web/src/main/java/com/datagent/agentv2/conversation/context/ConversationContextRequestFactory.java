package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.skill.runtime.ExecutionSkillViews;
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
    private final ExecutionSkillViews skillViews;

    public ConversationContextRequest create(
            LocalAgentExecution run,
            ModelInvocationCoordinator.ModelInvocationBoundary boundary,
            List<ModelToolDefinition> tools) {
        AgentExecution execution = executions.findByExecutionId(run.executionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }

        String skillPrompt = skillViews.require(run.executionId()).systemPrompt();
        List<ContextMessage> fixedSystemMessages =
                skillPrompt.isBlank() ? List.of() : List.of(ContextMessage.system(skillPrompt));
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
