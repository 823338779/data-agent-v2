package com.datagent.agentv2.dbt;

import static com.datagent.agentv2.dbt.DbtServiceClientTest.*;
import static com.datagent.agentv2.dbt.DbtServiceContractIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.dbt.model.DbtToolOutput;
import com.datagent.agentv2.dbt.port.*;
import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** v3分析迁移仍保障固定身份、时间语义、稳定重试键与有界完整行。 */
class DbtAnalysisToolsTest {
    private final DbtServiceClient client = mock(DbtServiceClient.class);
    private final BuildContextResolver builds = mock(BuildContextResolver.class);
    private final AnalysisSessionResolver sessions = context -> "session";
    private final InvocationContext context =
            new InvocationContext("tenant", "execution", 1, "call", "ignored");

    private DbtAnalysisTool tool(DbtAnalysisTool.Kind kind) throws Exception {
        when(builds.resolve("session", "execution")).thenReturn(BUILD);
        return new DbtAnalysisTool(kind, client, JSON, builds, sessions);
    }

    @Test
    void resourceSchemaAdvertisesOnlySupportedLowercaseViews() throws Exception {
        assertThat(
                        tool(DbtAnalysisTool.Kind.RESOURCE)
                                .parametersSchema()
                                .path("properties")
                                .path("view")
                                .path("enum"))
                .isEqualTo(JSON.readTree("[\"detail\",\"source\",\"lineage\",\"native-details\"]"));
    }

    @Test
    void analysisPinsBuildAcrossOptionsAndResults() throws Exception {
        when(client.catalog(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(fixture("catalog"), CatalogPage.class));
        when(client.submitOptions(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(fixture("options-queued"), OptionsTaskView.class));
        when(client.getOptions(BUILD))
                .thenReturn(JSON.treeToValue(fixture("options-queued"), OptionsTaskView.class));
        when(client.submitQuery(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(fixture("query-status"), QueryView.class));
        when(client.queryStatus(BUILD))
                .thenReturn(JSON.treeToValue(fixture("query-status"), QueryView.class));
        when(client.queryResults(BUILD, 0, 100))
                .thenReturn(JSON.treeToValue(fixture("precision-result"), ResultPage.class));
        tool(DbtAnalysisTool.Kind.CATALOG).execute(JSON.readTree("{}"), context);
        tool(DbtAnalysisTool.Kind.OPTIONS)
                .execute(JSON.readTree("{\"metricResourceIds\":[\"metric.revenue\"]}"), context);
        tool(DbtAnalysisTool.Kind.OPTIONS)
                .execute(JSON.readTree("{\"optionsTaskId\":\"" + BUILD + "\"}"), context);
        tool(DbtAnalysisTool.Kind.SUBMIT)
                .execute(
                        JSON.readTree(
                                "{\"mode\":\"QUERY\",\"metricResourceIds\":[\"metric.revenue\"]}"),
                        context);
        var result =
                tool(DbtAnalysisTool.Kind.GET)
                        .execute(JSON.readTree("{\"queryId\":\"" + BUILD + "\"}"), context);
        assertThat(result.path("buildId").asString()).isEqualTo(BUILD.toString());
        verify(builds, times(5)).resolve("session", "execution");
        verify(client, never()).currentDeployment(any());
    }

    @Test
    void largeRequestedPageIsCappedWithoutSkippingTheServerCursor() throws Exception {
        when(client.queryStatus(BUILD))
                .thenReturn(JSON.treeToValue(fixture("query-status"), QueryView.class));
        when(client.queryResults(BUILD, 0, 200))
                .thenReturn(JSON.treeToValue(fixture("precision-result"), ResultPage.class));
        var result =
                tool(DbtAnalysisTool.Kind.GET)
                        .execute(
                                JSON.readTree("{\"queryId\":\"" + BUILD + "\",\"limit\":10000}"),
                                context);
        verify(client).queryResults(BUILD, 0, 200);
        assertThat(result.path("returnedRows").asInt()).isEqualTo(1);
        assertThat(result.path("nextOffset").isNull()).isTrue();
    }

    @Test
    void pollingRejectsTaskFromDifferentBuildBeforeFetchingResults() throws Exception {
        var node = fixture("query-status").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node)
                .put("buildId", UUID.randomUUID().toString());
        when(client.queryStatus(BUILD)).thenReturn(JSON.treeToValue(node, QueryView.class));
        assertCode(
                () ->
                        tool(DbtAnalysisTool.Kind.GET)
                                .execute(JSON.readTree("{\"queryId\":\"" + BUILD + "\"}"), context),
                "DBT_PROTOCOL_INVALID");
        verify(client, never()).queryResults(any(), anyInt(), anyInt());
    }

    @Test
    void optionsPollingRejectsAnotherBuild() throws Exception {
        var node = fixture("options-queued").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node)
                .put("buildId", UUID.randomUUID().toString());
        when(client.getOptions(BUILD)).thenReturn(JSON.treeToValue(node, OptionsTaskView.class));
        assertCode(
                () ->
                        tool(DbtAnalysisTool.Kind.OPTIONS)
                                .execute(
                                        JSON.readTree("{\"optionsTaskId\":\"" + BUILD + "\"}"),
                                        context),
                "DBT_PROTOCOL_INVALID");
    }

    @Test
    void modelCannotOverrideBuildOrSessionTarget() throws Exception {
        for (String field :
                List.of(
                        "buildId",
                        "repository",
                        "branchName",
                        "sessionId",
                        "releaseId",
                        "workspaceId"))
            assertThatThrownBy(
                            () ->
                                    tool(DbtAnalysisTool.Kind.CATALOG)
                                            .execute(
                                                    JSON.createObjectNode().put(field, "attacker"),
                                                    context))
                    .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void missingSelectionNeverFallsBackToProduction() throws Exception {
        var tool = tool(DbtAnalysisTool.Kind.CATALOG);
        when(builds.resolve("session", "execution")).thenReturn(null);
        assertCode(() -> tool.execute(JSON.readTree("{}"), context), "DBT_BUILD_NOT_SELECTED");
        verifyNoInteractions(client);
    }

    @Test
    void submitPreservesTimeInputAndStableKeyOnRetry() throws Exception {
        when(client.submitQuery(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(fixture("query-status"), QueryView.class));
        var args =
                JSON.readTree(
                        "{\"mode\":\"QUERY\",\"metricResourceIds\":[\"metric.revenue\"],\"startTime\":\"2026-10-01T00:00:00\",\"endTime\":\"2026-10-02T08:00:00+08:00\"}");
        tool(DbtAnalysisTool.Kind.SUBMIT).execute(args, context);
        tool(DbtAnalysisTool.Kind.SUBMIT)
                .execute(
                        args,
                        new InvocationContext(
                                "tenant", "execution", 2, "another-call", "another-key"));
        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).submitQuery(eq(BUILD), captor.capture());
        var first = captor.getAllValues().getFirst();
        assertThat(captor.getAllValues().getLast()).isEqualTo(first);
        assertThat(first.startTime()).isEqualTo("2026-10-01T00:00:00");
        assertThat(first.endTime()).isEqualTo("2026-10-02T08:00:00+08:00");
    }

    @Test
    void stableKeyIgnoresJsonPropertyOrderAndDefaultArrays() throws Exception {
        var one = JSON.readTree("{\"mode\":\"QUERY\",\"metricResourceIds\":[\"b\",\"a\"]}");
        var two =
                JSON.readTree(
                        "{\"metricResourceIds\":[\"a\",\"b\"],\"mode\":\"QUERY\",\"limit\":1000,\"filters\":[],\"groupBy\":[],\"orderBy\":[]}");
        assertThat(DbtAnalysisTool.queryKey("execution", one, JSON))
                .isEqualTo(DbtAnalysisTool.queryKey("execution", two, JSON))
                .isNotEqualTo(DbtAnalysisTool.queryKey("another", one, JSON));
    }

    @Test
    void localCroppingNeverSkipsRows() throws Exception {
        var value =
                JSON.createObjectNode()
                        .put("offset", 37)
                        .put("nextOffset", 337)
                        .put("returnedRows", 300);
        var rows = value.putArray("rows");
        for (int i = 0; i < 300; i++) rows.addArray().add(i).add("x".repeat(100));
        var result = DbtToolOutput.bounded(value, JSON);
        assertThat(result.path("rows").size()).isLessThanOrEqualTo(200);
        assertThat(result.path("nextOffset").asInt()).isEqualTo(37 + result.path("rows").size());
        assertThat(result.path("returnedRows").asInt()).isEqualTo(result.path("rows").size());
        assertThat(result.path("localTruncated").asBoolean()).isTrue();
    }

    @Test
    void singleOversizedRowFailsRatherThanTruncatingCells() throws Exception {
        var value = JSON.createObjectNode();
        value.putArray("rows").addArray().add("x".repeat(21000));
        assertCode(() -> DbtToolOutput.bounded(value, JSON), "DBT_OUTPUT_TOO_LARGE");
    }
}
