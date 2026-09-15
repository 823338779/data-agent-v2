package com.datagent.agentv2.git.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitSessionResolver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class GitToolsTest {
    final ObjectMapper json = new ObjectMapper();
    final GitWorkspaceService workspaces = mock(GitWorkspaceService.class);
    final GitChangeService changes = mock(GitChangeService.class);
    final AgentTool.InvocationContext invocation =
            new AgentTool.InvocationContext("default", "exec-1", 1, "call-1", "trusted-key");
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(Scan.class)
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
                                            100000))
                    .withBean(GitWorkspaceService.class, () -> workspaces)
                    .withBean(GitChangeService.class, () -> changes)
                    .withBean(
                            GitSessionResolver.class,
                            () ->
                                    execution -> {
                                        assertThat(execution).isEqualTo("exec-1");
                                        return "session-1";
                                    });

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.datagent.agentv2.git.tool")
    static class Scan {}

    @Test
    void exposesOnlySevenDraftToolsAndRejectsInjectedServerArguments() {
        runner.run(
                ctx -> {
                    var tools = ctx.getBeansOfType(AgentTool.class).values();
                    assertThat(tools)
                            .extracting(AgentTool::name)
                            .containsExactlyInAnyOrder(
                                    "git_workspace_prepare",
                                    "git_files_list",
                                    "git_files_search",
                                    "git_file_read",
                                    "git_files_apply",
                                    "git_diff",
                                    "git_change_prepare");
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
    void candidateUsesTrustedIdempotencyAndSanitizesGitErrors() {
        when(changes.prepare("session-1", "exec-1", "trusted-key", "调整指标", "fix metrics", 3))
                .thenThrow(new GitException("GIT_COMMAND_FAILED", "stderr password secret"));
        runner.run(
                ctx -> {
                    var tool = tool(ctx, "git_change_prepare");
                    assertThat(tool.supportsIdempotency()).isTrue();
                    assertThat(tool.timeout()).isGreaterThan(Duration.ofSeconds(30));
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"summary\":\"调整指标\",\"commitMessage\":\"fix metrics\",\"expectedRevision\":3}"),
                                                    invocation))
                            .isInstanceOf(ToolBusinessException.class)
                            .hasMessageNotContaining("stderr")
                            .hasMessageNotContaining("secret");
                    verify(changes)
                            .prepare(
                                    "session-1", "exec-1", "trusted-key", "调整指标", "fix metrics", 3);
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
                                                            "{\"operations\":[{\"type\":\"UPDATE\",\"path\":\"metric.yml\",\"content\":\"new\"}]}"),
                                                    invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"operations\":[{\"type\":\"CREATE\",\"path\":\"metric.yml\",\"content\":\"new\",\"sessionId\":\"other\"}]}"),
                                                    invocation))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(
                                    () ->
                                            tool.execute(
                                                    json.readTree(
                                                            "{\"operations\":[{\"type\":\"CREATE\",\"path\":\"../metric.yml\",\"content\":\"new\"}]}"),
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

    private AgentTool tool(org.springframework.context.ApplicationContext ctx, String name) {
        return ctx.getBeansOfType(AgentTool.class).values().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Git tool: " + name));
    }
}
