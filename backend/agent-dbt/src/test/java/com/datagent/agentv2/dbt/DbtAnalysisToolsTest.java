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

/** 分析工具保障固定身份、时间语义、无状态查询与有界完整结果。 */
class DbtAnalysisToolsTest {
    private final DbtServiceClient client = mock(DbtServiceClient.class);
    private final BuildContextResolver builds = mock(BuildContextResolver.class);
    private final AnalysisSessionResolver sessions = context -> "session";
    private final InvocationContext context =
            new InvocationContext("tenant", "execution", 1, "call", "ignored");

    private DbtAnalysisTool tool(DbtAnalysisTool.Kind kind) throws Exception {
        when(builds.resolve("session", "execution")).thenReturn(BUILD);
        return new DbtAnalysisTool(
                kind, client, JSON, builds, sessions, java.time.Duration.ofSeconds(1860));
    }

    @Test
    void optionsRejectPollingAndRequireMetrics() throws Exception {
        var options = tool(DbtAnalysisTool.Kind.OPTIONS);
        for (var args :
                List.of(
                        JSON.readTree("{}"),
                        JSON.createObjectNode().put("optionsTaskId", BUILD.toString()))) {
            assertThatThrownBy(() -> options.execute(args, context))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(client);
    }

    @Test
    void toolBudgetExceedsConfiguredSynchronousRequestBudget() throws Exception {
        var properties =
                new com.datagent.agentv2.dbt.config.DbtProperties(
                        true,
                        java.net.URI.create("http://127.0.0.1:8000"),
                        java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofSeconds(1860),
                        null);
        assertThat(tool(DbtAnalysisTool.Kind.SUBMIT).timeout())
                .isEqualTo(properties.requestTimeout().plusSeconds(15));
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
                .thenReturn(
                        JSON.treeToValue(
                                fixture("available-dimensions-result"),
                                AvailableDimensionsView.class));
        when(client.submitQuery(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(fixture("precision-result"), QueryResult.class));
        tool(DbtAnalysisTool.Kind.CATALOG).execute(JSON.readTree("{}"), context);
        tool(DbtAnalysisTool.Kind.OPTIONS)
                .execute(JSON.readTree("{\"metricResourceIds\":[\"metric.revenue\"]}"), context);
        var result =
                tool(DbtAnalysisTool.Kind.SUBMIT)
                        .execute(
                                JSON.readTree(
                                        "{\"mode\":\"QUERY\",\"metricResourceIds\":[\"metric.revenue\"]}"),
                                context);
        assertThat(result.path("buildId").asString()).isEqualTo(BUILD.toString());
        verify(builds, times(3)).resolve("session", "execution");
        verify(client, never()).currentDeployment(any());
    }

    @Test
    void synchronousQueryRejectsDifferentBuild() throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) fixture("precision-result").deepCopy();
        node.put("buildId", UUID.randomUUID().toString());
        when(client.submitQuery(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(node, QueryResult.class));
        assertCode(
                () ->
                        tool(DbtAnalysisTool.Kind.SUBMIT)
                                .execute(JSON.readTree("{\"mode\":\"QUERY\"}"), context),
                "DBT_PROTOCOL_INVALID");
    }

    @Test
    void thousandSmallRowsAreReturnedWithoutLegacyPageLimit() throws Exception {
        var value = JSON.createObjectNode();
        var rows = value.putArray("rows");
        for (int i = 0; i < 1000; i++) rows.addArray().add(i);
        assertThat(DbtToolOutput.bounded(value, JSON)).isEqualTo(value);
        assertThat(value.path("rows").size()).isEqualTo(1000);
    }

    @Test
    void synchronousOptionsRejectAnotherBuild() throws Exception {
        var node = fixture("available-dimensions-result").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node)
                .put("buildId", UUID.randomUUID().toString());
        when(client.submitOptions(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(node, AvailableDimensionsView.class));
        assertCode(
                () ->
                        tool(DbtAnalysisTool.Kind.OPTIONS)
                                .execute(
                                        JSON.readTree(
                                                "{\"metricResourceIds\":[\"metric.revenue\"]}"),
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
    void submitPreservesTimeInputWithoutAddingServiceIdempotencyKey() throws Exception {
        when(client.submitQuery(eq(BUILD), any()))
                .thenReturn(JSON.treeToValue(fixture("precision-result"), QueryResult.class));
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
        assertThat(JSON.valueToTree(first).propertyNames())
                .doesNotContain("idempotencyKey", "offset");
    }

    @Test
    void modelCannotSendRemovedQueryParameters() throws Exception {
        for (String field : List.of("idempotencyKey", "offset", "queryId")) {
            var args = JSON.createObjectNode().put("mode", "QUERY").put(field, "removed");
            assertThatThrownBy(() -> tool(DbtAnalysisTool.Kind.SUBMIT).execute(args, context))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(client);
    }

    @Test
    void oversizedStatelessResultFailsWithoutDroppingRows() throws Exception {
        var value =
                JSON.createObjectNode()
                        .put("offset", 37)
                        .put("nextOffset", 337)
                        .put("returnedRows", 300);
        var rows = value.putArray("rows");
        for (int i = 0; i < 300; i++) rows.addArray().add(i).add("x".repeat(100));
        assertCode(() -> DbtToolOutput.bounded(value, JSON), "DBT_OUTPUT_TOO_LARGE");
        assertThat(value.path("rows").size()).isEqualTo(300);
    }

    @Test
    void singleOversizedRowFailsRatherThanTruncatingCells() throws Exception {
        var value = JSON.createObjectNode();
        value.putArray("rows").addArray().add("x".repeat(21000));
        assertCode(() -> DbtToolOutput.bounded(value, JSON), "DBT_OUTPUT_TOO_LARGE");
    }
}
