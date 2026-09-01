package com.datagent.agentv2.conversation.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.runtime.DataAnalysisWorkflowEngine;
import com.datagent.agentv2.workflow.runtime.ExecutionRouteResolver;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutedAgentExecutionEngineTest {

    @Test
    void dataRouteExecutesWorkflowWithoutReact() {
        WorkflowInputFactory inputs = mock(WorkflowInputFactory.class);
        ExecutionRouteResolver routes = mock(ExecutionRouteResolver.class);
        DataAnalysisWorkflowEngine workflow = mock(DataAnalysisWorkflowEngine.class);
        ReactExecutionEngine react = mock(ReactExecutionEngine.class);
        LocalAgentExecution run = run();
        when(inputs.create(run)).thenReturn(request());
        when(routes.resolve(any())).thenReturn(ExecutionRoute.DATA_ANALYSIS_WORKFLOW);
        new RoutedAgentExecutionEngine(inputs, routes, Optional.of(workflow), react).execute(run);
        verify(workflow).execute(any());
        verify(react, never()).execute(any());
    }

    @Test
    void generalRouteExecutesReactWithoutWorkflow() {
        WorkflowInputFactory inputs = mock(WorkflowInputFactory.class);
        ExecutionRouteResolver routes = mock(ExecutionRouteResolver.class);
        DataAnalysisWorkflowEngine workflow = mock(DataAnalysisWorkflowEngine.class);
        ReactExecutionEngine react = mock(ReactExecutionEngine.class);
        LocalAgentExecution run = run();
        when(inputs.create(run)).thenReturn(request());
        when(routes.resolve(any())).thenReturn(ExecutionRoute.GENERAL_REACT);
        new RoutedAgentExecutionEngine(inputs, routes, Optional.of(workflow), react).execute(run);
        verify(react).execute(run);
        verify(workflow, never()).execute(any());
    }

    private static LocalAgentExecution run() {
        return new LocalAgentExecution("tenant", "session", "execution", 1);
    }

    private static WorkflowRunRequest request() {
        return new WorkflowRunRequest(
                "tenant",
                "session",
                "execution",
                1,
                "问题",
                List.of(),
                Instant.now().plusSeconds(60));
    }
}
