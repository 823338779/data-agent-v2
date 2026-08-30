package com.datagent.agentv2.conversation.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.tool.AgentTool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CurrentTimeToolTest {

    @Test
    void currentTimeUsesOneClockSnapshotForAllFields() throws Exception {
        Clock clock =
                Clock.fixed(Instant.parse("2026-08-30T01:02:03Z"), ZoneId.of("Asia/Shanghai"));
        CurrentTimeTool tool = new CurrentTimeTool(clock);

        JsonNode result = tool.execute(JsonMapper.builder().build().createObjectNode(), context());

        assertThat(result.path("instant").asString()).isEqualTo("2026-08-30T01:02:03Z");
        assertThat(result.path("localDateTime").asString()).isEqualTo("2026-08-30T09:02:03");
        assertThat(result.path("zoneId").asString()).isEqualTo("Asia/Shanghai");
        assertThat(result.path("offset").asString()).isEqualTo("+08:00");
        assertThat(tool.parametersSchema().path("additionalProperties").asBoolean()).isFalse();
    }

    private static AgentTool.InvocationContext context() {
        return new AgentTool.InvocationContext("tenant", "execution", 1L, "call", "key");
    }
}
