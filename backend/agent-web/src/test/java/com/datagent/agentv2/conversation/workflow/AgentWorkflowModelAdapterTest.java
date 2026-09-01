package com.datagent.agentv2.conversation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.RoutePrompt;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentWorkflowModelAdapterTest {

    @Test
    void routeCallsModelWithoutTools() {
        AgentModelGateway model = mock(AgentModelGateway.class);
        when(model.complete(anyList(), anyList(), any()))
                .thenReturn(new ModelInvocationResult.FinalAnswer("{\"route\":\"GENERAL_REACT\"}"));
        AgentWorkflowModelAdapter adapter =
                new AgentWorkflowModelAdapter(
                        model, new WorkflowJsonDecoder(JsonMapper.builder().build()));
        assertThat(adapter.route(new RoutePrompt("你好", List.of())))
                .isEqualTo(ExecutionRoute.GENERAL_REACT);
        verify(model).complete(anyList(), org.mockito.ArgumentMatchers.eq(List.of()), any());
    }
}
