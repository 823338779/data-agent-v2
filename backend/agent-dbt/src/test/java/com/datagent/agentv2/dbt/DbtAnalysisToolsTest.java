package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.dbt.model.DbtToolOutput;
import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 输出预算须以实际展示行推进游标，同次执行的等价请求复用身份。 */
class DbtAnalysisToolsTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void unknownRemoteStateFailsClosed() throws Exception {
        var client =
                org.mockito.Mockito.mock(com.datagent.agentv2.dbt.client.DbtServiceClient.class);
        org.mockito.Mockito.when(client.project())
                .thenReturn(
                        json.readTree(
                                "{\"protocolVersion\":\"agent-dbt-v1\",\"capabilities\":[\"query-options-async-v1\",\"query-results-page-v1\",\"query-time-v1\"]}"));
        org.mockito.Mockito.when(client.queryStatus("q"))
                .thenReturn(
                        json.readTree("{\"queryId\":\"q\",\"state\":\"UNKNOWN_FUTURE_STATE\"}"));
        var tool = new DbtAnalysisTool(DbtAnalysisTool.Kind.GET, client, json);
        assertThatThrownBy(
                        () ->
                                tool.execute(
                                        json.readTree("{\"queryId\":\"q\"}"),
                                        new com.datagent.agentv2.conversation.tool.AgentTool
                                                .InvocationContext("t", "e", 1, "c", "k")))
                .isInstanceOf(
                        com.datagent.agentv2.conversation.exception.ToolBusinessException.class);
    }

    @Test
    void defaultParametersAndEquivalentOffsetsHaveSameKey() {
        var a = json.readTree("{\"mode\":\"QUERY\",\"startTime\":\"2026-01-01T00:00:00Z\"}");
        var b =
                json.readTree(
                        "{\"mode\":\"QUERY\",\"startTime\":\"2026-01-01T08:00:00+08:00\",\"limit\":1000,\"filters\":[],\"groupBy\":[],\"orderBy\":[],\"metricResourceIds\":[]}");
        assertThat(DbtAnalysisTool.queryKey("e", a, json))
                .isEqualTo(DbtAnalysisTool.queryKey("e", b, json));
    }

    @Test
    void localCroppingNeverSkipsRows() throws Exception {
        var page = json.createObjectNode();
        page.put("offset", 10).put("nextOffset", 13).put("availableRows", 20);
        var rows = page.putArray("rows");
        for (int i = 0; i < 3; i++) rows.addArray().add("x".repeat(9000));
        var output = DbtToolOutput.bounded(page, json);
        assertThat(output.path("rows").size()).isEqualTo(2);
        assertThat(output.path("nextOffset").asInt()).isEqualTo(12);
        assertThat(json.writeValueAsString(output).length()).isLessThanOrEqualTo(20000);
    }

    @Test
    void stableKeyIgnoresJsonPropertyOrderAndToolCallIdentity() {
        var a = json.readTree("{\"mode\":\"QUERY\",\"limit\":100}");
        var b = json.readTree("{\"limit\":100,\"mode\":\"QUERY\"}");
        assertThat(DbtAnalysisTool.queryKey("exec-1", a, json))
                .isEqualTo(DbtAnalysisTool.queryKey("exec-1", b, json));
        assertThat(DbtAnalysisTool.queryKey("exec-2", a, json))
                .isNotEqualTo(DbtAnalysisTool.queryKey("exec-1", a, json));
    }
}
