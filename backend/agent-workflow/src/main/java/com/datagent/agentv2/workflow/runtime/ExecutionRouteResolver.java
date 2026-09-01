package com.datagent.agentv2.workflow.runtime;

import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.RoutePrompt;
import com.datagent.agentv2.workflow.port.WorkflowModelPort;
import lombok.RequiredArgsConstructor;

/** 顶层路由失败时保守回退通用 ReAct，且不会创建半成品 Workflow Run。 */
@RequiredArgsConstructor
public final class ExecutionRouteResolver {

    private final WorkflowModelPort model;

    public ExecutionRoute resolve(RoutePrompt prompt) {
        try {
            ExecutionRoute route = model.route(prompt);
            return route == null ? ExecutionRoute.GENERAL_REACT : route;
        } catch (RuntimeException ignored) {
            return ExecutionRoute.GENERAL_REACT;
        }
    }
}
