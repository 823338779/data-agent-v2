package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.memory.MemoryService;
import com.datagent.agentv2.memory.WebMemoryEvidenceAdapter;
import com.datagent.agentv2.memory.tool.MemoryInvocationRegistry;
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
    private final MemoryService memories;
    private final WebMemoryEvidenceAdapter memoryEvidence;
    private final MemoryInvocationRegistry memoryInvocations;

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
        ConversationContextRequest request =
                ConversationContextRequest.composeForInvocation(
                new ContextExecutionRef(
                        run.tenantId(),
                        execution.getConversationSessionId(),
                        run.executionId(),
                        boundary.inputUpToConversationMessageSequence()),
                budgets.currentBudget(),
                fixedSystemMessages,
                contextTools);
        // 工具在后续虚拟线程运行；模型调用前以显式 invocation 键冻结 revision 与允许证据。
        memoryInvocations.open(
                run.executionId(),
                boundary.modelInvocationSequence(),
                memories.snapshot(),
                memoryEvidence.currentUserEvidence(run.executionId()));
        return request;
    }
}
