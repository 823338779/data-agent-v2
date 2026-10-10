package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.development.application.DevelopmentWorkspaceService;
import com.datagent.agentv2.development.application.SourceSaveService;
import com.datagent.agentv2.development.tool.*;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class DevelopmentFileToolsTest {
    final ObjectMapper json = new ObjectMapper();
    final DevelopmentWorkspaceService workspaces = mock(DevelopmentWorkspaceService.class);
    final SourceSaveService changes = mock(SourceSaveService.class);
    final AgentTool.InvocationContext invocation =
            new AgentTool.InvocationContext("default", "exec-1", 1, "call-1", "trusted-key");
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            DevelopmentToolSupport.class,
                            DevelopmentFilesListTool.class,
                            DevelopmentFilesSearchTool.class,
                            DevelopmentFileReadTool.class,
                            DevelopmentFilesApplyTool.class,
                            DevelopmentDiffTool.class,
                            DevelopmentSaveTool.class)
                    .withPropertyValues("data-agent.git.enabled=true")
                    .withBean(ObjectMapper.class, () -> json)
                    .withBean(
                            GitProperties.class,
                            () ->
                                    new GitProperties(
                                            "git",
                                            "unused",
                                            Path.of("unused"),
                                            Duration.ofSeconds(30),
                                            100000,
                                            100000,
                                            20,
                                            100,
                                            1000000,
                                            2097152))
                    .withBean(DevelopmentWorkspaceService.class, () -> workspaces)
                    .withBean(SourceSaveService.class, () -> changes)
                    .withBean(
                            AnalysisSessionResolver.class,
                            () ->
                                    execution -> {
                                        assertThat(execution.executionId()).isEqualTo("exec-1");
                                        return "session-1";
                                    });

    @org.junit.jupiter.api.BeforeEach
    void executeWorkspaceLockCallback() {
        when(workspaces.locked(anyString(), any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
    }

    @Test
    void exposesFiveFileToolsAndSaveAndRejectsInjectedServerArguments() {
        runner.run(
                ctx -> {
                    var tools = ctx.getBeansOfType(AgentTool.class).values();
                    assertThat(tools)
                            .extracting(AgentTool::name)
                            .containsExactlyInAnyOrder(
                                    "git_files_list",
                                    "git_files_search",
                                    "git_file_read",
                                    "git_files_apply",
                                    "git_diff",
                                    "development_save");
                    for (var tool : tools) {
                        assertThat(tool.parametersSchema().path("additionalProperties").asBoolean())
                                .isFalse();
                        assertThatThrownBy(
                                        () ->
                                                tool.execute(
                                                        json.readTree("{\"sessionId\":\"other\"}"),
                                                        invocation))
                                .isInstanceOf(IllegalArgumentException.class);
                    }
                    verifyNoInteractions(workspaces, changes);
                });
    }

    @Test
    void readKeepsFullDigestButBoundsLinesAndCharacters() {
        when(workspaces.read("session-1", "metric.yml"))
                .thenReturn(
                        new FileReadResult("metric.yml", "first\nsecond\nthird", "full-digest"));
        runner.run(
                ctx -> {
                    var result =
                            tool(ctx, "git_file_read")
                                    .execute(
                                            json.readTree(
                                                    "{\"path\":\"metric.yml\",\"startLine\":2,\"lineCount\":1}"),
                                            invocation);
                    assertThat(result.path("content").asString()).isEqualTo("second");
                    assertThat(result.path("sha256").asString()).isEqualTo("full-digest");
                    assertThat(result.path("truncated").asBoolean()).isTrue();
                    assertThatThrownBy(
                                    () ->
                                            tool(ctx, "git_file_read")
                                                    .execute(
                                                            json.readTree("{\"path\":\"/secret\"}"),
                                                            invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(
                                    () ->
                                            tool(ctx, "git_file_read")
                                                    .execute(
                                                            json.readTree(
                                                                    "{\"path\":\"metric.yml\",\"lineCount\":999999999999}"),
                                                            invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void listAndDiffArePaginatedAndLongLinesAreBounded() {
        when(workspaces.list("session-1", "")).thenReturn(List.of("a", "b", "c"));
        when(workspaces.diff("session-1"))
                .thenReturn(
                        new GitSnapshot(
                                "tree",
                                "digest",
                                "x".repeat(30000),
                                List.of(new GitFileChange("M", "a"))));
        runner.run(
                ctx -> {
                    var page =
                            tool(ctx, "git_files_list")
                                    .execute(
                                            json.readTree("{\"offset\":1,\"limit\":1}"),
                                            invocation);
                    assertThat(page.path("files").get(0).asString()).isEqualTo("b");
                    assertThat(page.path("nextOffset").asInt()).isEqualTo(2);
                    var diff = tool(ctx, "git_diff").execute(json.readTree("{}"), invocation);
                    assertThat(diff.toString().length()).isLessThan(22000);
                    assertThat(diff.path("truncated").asBoolean()).isTrue();
                });
    }

    @Test
    void saveUsesTrustedIdempotencyAndPropagatesStableErrorCode() {
        when(changes.start(eq("session-1"), any()))
                .thenThrow(
                        new com.datagent.agentv2.development.model.DevelopmentException(
                                "GIT_COMMAND_FAILED", "Git命令失败", 503));
        runner.run(
                ctx -> {
                    var tool = tool(ctx, "development_save");
                    assertThat(tool.supportsIdempotency()).isTrue();
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"commitMessage\":\"fix"
                                                                    + " metrics\",\"expectedRevision\":3}"),
                                                    invocation))
                            .isInstanceOf(ToolBusinessException.class)
                            .hasMessageNotContaining("stderr");
                    verify(changes)
                            .start(
                                    "session-1",
                                    new com.datagent.agentv2.development.model.DevelopmentContracts
                                            .SaveRequest("trusted-key", 3, "fix metrics"));
                });
    }

    @Test
    void applyRejectsMissingDigestAndNestedAuthorityBeforeMutating() {
        runner.run(
                ctx -> {
                    var tool = tool(ctx, "git_files_apply");
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"expectedRevision\":0,\"operations\":[{\"type\":\"UPDATE\",\"path\":\"metric.yml\",\"content\":\"new\"}]}"),
                                                    invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"expectedRevision\":0,\"operations\":[{\"type\":\"CREATE\",\"path\":\"metric.yml\",\"content\":\"new\",\"sessionId\":\"other\"}]}"),
                                                    invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"expectedRevision\":0,\"operations\":[{\"type\":\"CREATE\",\"path\":\"../metric.yml\",\"content\":\"new\"}]}"),
                                                    invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                    verifyNoInteractions(workspaces);
                });
    }

    @Test
    void searchReturnsBoundedPositionsAndPagination() {
        when(workspaces.search("session-1", "", "metric"))
                .thenReturn(
                        List.of(
                                new FileSearchMatch("a", 3, "metric"),
                                new FileSearchMatch("b", 5, "metric".repeat(500))));
        runner.run(
                ctx -> {
                    var result =
                            tool(ctx, "git_files_search")
                                    .execute(
                                            json.readTree(
                                                    "{\"query\":\"metric\",\"offset\":1,\"limit\":1}"),
                                            invocation);
                    assertThat(result.path("items").get(0).path("path").asString()).isEqualTo("b");
                    assertThat(result.path("items").get(0).path("line").asInt()).isEqualTo(5);
                    assertThat(result.path("items").get(0).path("text").asString()).hasSize(300);
                    assertThat(result.path("total").asInt()).isEqualTo(2);
                    assertThat(result.path("nextOffset").isNull()).isTrue();
                });
    }

    @Test
    void diffPaginationCrossesOneMillionAndReconstructsTheCompleteSupportedDiff() {
        String diff = "a".repeat(1000000) + "TAIL".repeat(125000);
        when(workspaces.diff("session-1"))
                .thenReturn(
                        new GitSnapshot(
                                "tree", "digest", diff, List.of(new GitFileChange("M", "a"))));
        runner.run(
                ctx -> {
                    var tool = tool(ctx, "git_diff");
                    StringBuilder collected = new StringBuilder();
                    int offset = 0;
                    while (true) {
                        var page =
                                tool.execute(
                                        json.createObjectNode().put("offset", offset), invocation);
                        String content = page.path("diff").asString();
                        assertThat(content.length()).isBetween(1, 12000);
                        collected.append(content);
                        if (page.path("nextOffset").isNull()) break;
                        int next = page.path("nextOffset").asInt();
                        assertThat(next).isEqualTo(offset + content.length());
                        offset = next;
                    }
                    assertThat(collected.toString()).isEqualTo(diff);
                });
    }

    @Test
    void diffOffsetsBeyondContentAreEmptyAndNumbersBeyondIntegerRangeAreRejected() {
        when(workspaces.diff("session-1"))
                .thenReturn(
                        new GitSnapshot(
                                "tree", "digest", "small", List.of(new GitFileChange("M", "a"))));
        runner.run(
                ctx -> {
                    var tool = tool(ctx, "git_diff");
                    var page =
                            tool.execute(
                                    json.createObjectNode()
                                            .put("offset", Integer.MAX_VALUE)
                                            .put("limit", 20000),
                                    invocation);
                    assertThat(page.path("diff").asString()).isEmpty();
                    assertThat(page.path("nextOffset").isNull()).isTrue();
                    for (long invalid : new long[] {-1, 2147483648L, Long.MAX_VALUE}) {
                        assertThatThrownBy(
                                        () ->
                                                tool.execute(
                                                        json.createObjectNode()
                                                                .put("offset", invalid),
                                                        invocation))
                                .isInstanceOf(IllegalArgumentException.class);
                    }
                });
    }

    private AgentTool tool(org.springframework.context.ApplicationContext ctx, String name) {
        return ctx.getBeansOfType(AgentTool.class).values().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Git tool: " + name));
    }
}
