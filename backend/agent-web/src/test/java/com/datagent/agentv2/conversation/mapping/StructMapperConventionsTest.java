package com.datagent.agentv2.conversation.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextModelMessageStructMapper;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
import com.datagent.agentv2.conversation.model.ollama.OllamaModelStructMapper;
import com.datagent.agentv2.conversation.react.ModelInputMessage;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeStructMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import tools.jackson.databind.json.JsonMapper;

class StructMapperConventionsTest {

    @Test
    void mapsSingleSourceContextAndRuntimeDataclasses() {
        ModelToolDefinition tool =
                new ModelToolDefinition("lookup", "Lookup data", Map.of("type", "object"));

        var mappedTool =
                ContextModelMessageStructMapper.INSTANCE.toContextToolDefinition(tool);
        AgentExecutionPort.ClaimedAgentExecution claimed =
                AgentExecutionRuntimeStructMapper.INSTANCE.toClaimedExecution(
                        new ContextExecutionRef(
                                "tenant-1", "session-1", "execution-1", "owner-1", 3L, 20L));

        assertThat(mappedTool.parameters()).containsEntry("type", "object");
        assertThat(claimed.executionAttemptNumber()).isEqualTo(3L);
    }

    @Test
    void mapsModelMessagesToOllamaProtocolWithoutGatewayAssembly() {
        JsonMapper json = JsonMapper.builder().build();
        ModelToolCall call =
                new ModelToolCall("call-1", "lookup", json.readTree("{\"query\":\"x\"}"));

        OllamaApi.Message mapped =
                OllamaModelStructMapper.INSTANCE.toProviderMessage(
                        ModelInputMessage.assistantToolCalls(List.of(call)), json);

        assertThat(mapped.role()).isEqualTo(OllamaApi.Message.Role.ASSISTANT);
        assertThat(mapped.toolCalls()).hasSize(1);
        assertThat(mapped.toolCalls().getFirst().function().arguments())
                .containsEntry("query", "x");
        assertThat(
                        OllamaModelStructMapper.INSTANCE
                                .toProviderMessage(ModelInputMessage.plain("user", "question"), json)
                                .toolCalls())
                .isNull();
    }
}
