package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextModelBudgetProvider;
import com.datagent.agentv2.conversation.context.ContextModelMessageMapper;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextStrategyResolver;
import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.react.ModelInvocationCoordinator;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.port.WorkflowFinalAnswerPort;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 复用现有持久化回答事务和 assistant_delta 流，且不给最终模型提供 Tool。 */
@Service
@RequiredArgsConstructor
public final class WorkflowFinalAnswerAdapter implements WorkflowFinalAnswerPort {

    private static final String FINAL_SYSTEM_PREFIX =
            """
            你正在生成数据分析最终回答。只能使用下列已经验证的分析摘要、结论、证据引用和限制，
            不得声称执行新查询，不得编造数据。用清晰中文直接回答用户，并明确说明 caveat。

            已验证分析 JSON：
            """;

    private final AgentModelGateway model;
    private final ModelInvocationCoordinator invocations;
    private final ConversationContextStrategyResolver contexts;
    private final ContextModelBudgetProvider budgets;
    private final ContextModelMessageMapper contextMessages;
    private final WorkflowJsonDecoder json;

    @Override
    public void complete(WorkflowRunRequest request, AnalysisResult analysis) {
        LocalAgentExecution run =
                new LocalAgentExecution(
                        request.tenantId(),
                        request.conversationSessionId(),
                        request.executionId(),
                        request.inputUpToMessageSequence());
        ModelInvocationCoordinator.ModelInvocationBoundary boundary =
                invocations.beginModelInvocation(run);
        ConversationContextRequest contextRequest =
                ConversationContextRequest.composeForInvocation(
                        new ContextExecutionRef(
                                request.tenantId(),
                                request.conversationSessionId(),
                                request.executionId(),
                                boundary.inputUpToConversationMessageSequence()),
                        budgets.currentBudget(),
                        List.of(ContextMessage.system(FINAL_SYSTEM_PREFIX + json.encode(analysis))),
                        List.of());
        ModelInvocationResult result;
        try {
            result =
                    model.complete(
                            contextMessages.toModelMessages(
                                    contexts.prepare(contextRequest).messages()),
                            List.of(),
                            delta -> invocations.checkpoint(run, boundary, delta));
        } catch (ModelStreamInterruptedException failure) {
            invocations.failModelOutput(run, boundary);
            throw failure;
        }
        if (!(result instanceof ModelInvocationResult.FinalAnswer answer)) {
            invocations.failModelOutput(run, boundary);
            throw new IllegalStateException("Workflow 最终回答不允许返回 ToolCalls");
        }
        invocations.completeFinalAnswer(run, boundary, answer.answer());
    }
}
