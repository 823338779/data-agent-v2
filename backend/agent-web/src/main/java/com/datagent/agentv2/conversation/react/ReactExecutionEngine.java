package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.model.AgentModelGateway;
import com.datagent.agentv2.conversation.model.ModelInvocationResult;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.tool.AgentToolRegistry;
import com.datagent.agentv2.conversation.tool.ToolExecutionCoordinator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class ReactExecutionEngine {

    private static final String ERROR_MODEL_INVOCATION_LIMIT =
            "ReAct model invocation limit exceeded";
    private static final String ERROR_TOOL_CALL_LIMIT = "ReAct tool call limit exceeded";

    private final AgentModelGateway model;
    private final ConversationContextLoader contexts;
    private final ModelInvocationCoordinator modelInvocationCoordinator;
    private final ToolExecutionCoordinator tools;
    private final AgentToolRegistry registry;
    private final AgentExecutionLeaseService leases;
    private final ReactLimitsProperties limits;

    public void execute(AgentExecutionPort.ClaimedAgentExecution run) {
        execute(run, ReactRecoveryPlanner.RecoveryPlan.continueModel());
    }

    public void execute(
            AgentExecutionPort.ClaimedAgentExecution run,
            ReactRecoveryPlanner.RecoveryPlan recovery) {
        int modelInvocationCount = 0;
        int toolCallCount = 0;
        Long recoveredInputBoundary =
                recovery.action() == ReactRecoveryPlanner.Action.RETRY_MODEL
                        ? recovery.inputBoundary()
                        : null;
        if (recovery.action() == ReactRecoveryPlanner.Action.EXECUTE_TOOLS) {
            tools.executeModelInvocationTools(run, recovery.modelInvocationSequence());
        }

        // 显式 ReAct 循环只在完整模型调用或完整工具结果边界之间推进。
        while (true) {
            leases.assertOwnedAndWithinBudget(run);
            if (leases.cancelIfRequested(run)) {
                return;
            }
            if (++modelInvocationCount > limits.getMaxModelInvocations()) {
                throw new ReactLimitExceededException(ERROR_MODEL_INVOCATION_LIMIT);
            }
            ModelInvocationCoordinator.ModelInvocationBoundary modelInvocation =
                    recoveredInputBoundary == null
                            ? modelInvocationCoordinator.beginModelInvocation(run)
                            : modelInvocationCoordinator.beginModelInvocation(
                                    run, recoveredInputBoundary);
            recoveredInputBoundary = null;
            List<ModelInputMessage> input =
                    contexts.load(
                            run.executionId(),
                            modelInvocation.inputUpToConversationMessageSequence());

            leases.assertOwnedAndWithinBudget(run);
            ModelInvocationResult result =
                    model.complete(
                            input,
                            registry.modelDefinitions(),
                            delta ->
                                    modelInvocationCoordinator.checkpoint(
                                            run, modelInvocation, delta));
            leases.assertOwnedAndWithinBudget(run);

            if (result instanceof ModelInvocationResult.ToolCalls toolCalls) {
                toolCallCount += toolCalls.calls().size();
                if (toolCallCount > limits.getMaxToolCalls()) {
                    throw new ReactLimitExceededException(ERROR_TOOL_CALL_LIMIT);
                }
                modelInvocationCoordinator.archiveToolCalls(
                        run, modelInvocation, toolCalls.calls());
                tools.executeModelInvocationTools(run, modelInvocation.modelInvocationSequence());
                continue;
            }
            ModelInvocationResult.FinalAnswer finalAnswer =
                    (ModelInvocationResult.FinalAnswer) result;
            modelInvocationCoordinator.completeFinalAnswer(
                    run, modelInvocation, finalAnswer.answer());
            return;
        }
    }
}
