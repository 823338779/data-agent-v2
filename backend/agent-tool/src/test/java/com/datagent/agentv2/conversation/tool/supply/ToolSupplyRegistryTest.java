package com.datagent.agentv2.conversation.tool.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.support.StubAgentTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ToolSupplyRegistryTest {

    @Test
    void rejectsDuplicateRuntimeNames() {
        AgentTool first = new StubAgentTool("duplicate");
        AgentTool second = new StubAgentTool("duplicate");

        assertThatThrownBy(() -> new ToolSupplyRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate agent tool: duplicate");
    }

    @Test
    void preservesRegistrationOrderAndBuildsStableFingerprint() {
        AgentTool first = new StubAgentTool("first");
        AgentTool second =
                new StubAgentTool(
                        "second",
                        "Second tool",
                        JsonMapper.builder().build().createObjectNode().put("type", "object"));

        ToolSupplyRegistry firstRegistry = new ToolSupplyRegistry(List.of(first, second));
        ToolSupplyRegistry secondRegistry = new ToolSupplyRegistry(List.of(first, second));

        assertThat(firstRegistry.names()).containsExactly("first", "second");
        assertThat(firstRegistry.bindingsInRegistrationOrder())
                .extracting(ToolBinding::runtimeName)
                .containsExactly("first", "second");
        assertThat(firstRegistry.require("second").fingerprint())
                .matches("[0-9a-f]{64}")
                .isEqualTo(secondRegistry.require("second").fingerprint());
    }

    @Test
    void rejectsBlankRuntimeName() {
        assertThatThrownBy(() -> new ToolSupplyRegistry(List.of(new StubAgentTool(" "))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
