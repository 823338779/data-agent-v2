package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.RoutePrompt;
import com.datagent.agentv2.workflow.runtime.DataAnalysisWorkflowEngine;
import com.datagent.agentv2.workflow.runtime.ExecutionRouteResolver;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 在同级的受控数据 Workflow 与通用 ReAct 之间选择一次且不再切换。 */
@Service
@RequiredArgsConstructor
public final class RoutedAgentExecutionEngine {

    private final WorkflowInputFactory workflowInputs;
    private final ExecutionRouteResolver routes;
    private final Optional<DataAnalysisWorkflowEngine> workflow;
    private final ReactExecutionEngine react;

    public void execute(LocalAgentExecution run) {
        if (workflow.isEmpty()) {
            react.execute(run);
            return;
        }
        WorkflowRunRequest request = workflowInputs.create(run);
        ExecutionRoute route =
                routes.resolve(new RoutePrompt(request.question(), request.recentTurns()));
        if (route == ExecutionRoute.DATA_ANALYSIS_WORKFLOW) {
            workflow.orElseThrow().execute(request);
            return;
        }
        react.execute(run);
    }
}
