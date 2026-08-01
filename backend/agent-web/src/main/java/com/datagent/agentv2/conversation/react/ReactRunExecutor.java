package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.model.AgentModelGateway;
import com.datagent.agentv2.conversation.model.ModelTurnResult;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
import com.datagent.agentv2.conversation.tool.AgentToolRegistry;
import com.datagent.agentv2.conversation.tool.ToolExecutionCoordinator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class ReactRunExecutor {

    private static final String ERROR_MODEL_TURN_LIMIT = "ReAct model turn limit exceeded";
    private static final String ERROR_TOOL_CALL_LIMIT = "ReAct tool call limit exceeded";

    private final AgentModelGateway model;
    private final ConversationContextLoader contexts;
    private final ModelTurnCoordinator turns;
    private final ToolExecutionCoordinator tools;
    private final AgentToolRegistry registry;
    private final RunLeaseService leases;
    private final ReactLimitsProperties limits;

    public void execute(RunExecutionPort.OwnedRun run) {
        execute(run, ReactRecoveryPlanner.RecoveryPlan.continueModel());
    }

    public void execute(RunExecutionPort.OwnedRun run, ReactRecoveryPlanner.RecoveryPlan recovery) {
        int modelTurnCount = 0;
        int toolCallCount = 0;
        Long recoveredInputBoundary =
                recovery.action() == ReactRecoveryPlanner.Action.RETRY_MODEL
                        ? recovery.inputBoundary()
                        : null;
        if (recovery.action() == ReactRecoveryPlanner.Action.EXECUTE_TOOLS) {
            tools.executeTurn(run, recovery.modelTurnSequence());
        }

        // 显式 ReAct 循环只在完整 Model Turn 或完整 Observation 边界之间推进。
        while (true) {
            leases.assertOwnedAndWithinBudget(run);
            if (leases.cancelIfRequested(run)) {
                return;
            }
            if (++modelTurnCount > limits.getMaxModelTurns()) {
                throw new ReactLimitExceededException(ERROR_MODEL_TURN_LIMIT);
            }
            ModelTurnCoordinator.TurnBoundary turn =
                    recoveredInputBoundary == null
                            ? turns.beginTurn(run)
                            : turns.beginTurn(run, recoveredInputBoundary);
            recoveredInputBoundary = null;
            List<ModelInputMessage> input =
                    contexts.load(run.runId(), turn.inputUpToMessageSequence());

            leases.assertOwnedAndWithinBudget(run);
            ModelTurnResult result =
                    model.complete(
                            input,
                            registry.modelDefinitions(),
                            delta -> turns.checkpoint(run, turn, delta));
            leases.assertOwnedAndWithinBudget(run);

            if (result instanceof ModelTurnResult.ToolCalls toolCalls) {
                toolCallCount += toolCalls.calls().size();
                if (toolCallCount > limits.getMaxToolCalls()) {
                    throw new ReactLimitExceededException(ERROR_TOOL_CALL_LIMIT);
                }
                turns.archiveToolCalls(run, turn, toolCalls.calls());
                tools.executeTurn(run, turn.modelTurnSequence());
                continue;
            }
            ModelTurnResult.FinalAnswer finalAnswer = (ModelTurnResult.FinalAnswer) result;
            turns.completeFinalAnswer(run, turn, finalAnswer.answer());
            return;
        }
    }
}
