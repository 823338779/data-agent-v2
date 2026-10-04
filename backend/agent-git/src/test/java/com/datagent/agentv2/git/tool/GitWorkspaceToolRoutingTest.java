package com.datagent.agentv2.git.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.git.application.GitChangeService;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.GitWorkspaceContext;
import com.datagent.agentv2.git.port.GitSessionResolver;
import com.datagent.agentv2.git.port.GitWorkspaceResolver;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 同一会话的工具以执行冻结的目标路由，不能接受模型覆盖工作区。 */
class GitWorkspaceToolRoutingTest {
    private static final String SESSION = "same-session";
    private static final String A = "workspace-a";
    private static final String B = "workspace-b";
    private static final String EXECUTION_A = "execution-a";
    private static final String SEARCH = "{\"directory\":\"\",\"query\":\"metric\"}";
    private static final String INJECTED = "{\"query\":\"metric\",\"workspaceId\":\"attacker\"}";
    private static final String EMPTY = "";
    private static final String QUERY = "metric";
    private final ObjectMapper json = new ObjectMapper();
    private final GitWorkspaceService workspaces = mock(GitWorkspaceService.class);
    private final GitChangeService changes = mock(GitChangeService.class);
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(Scan.class)
                    .withPropertyValues("data-agent.git.enabled=true")
                    .withBean(ObjectMapper.class, () -> json)
                    .withBean(GitWorkspaceService.class, () -> workspaces)
                    .withBean(GitChangeService.class, () -> changes)
                    .withBean(
                            GitProperties.class,
                            () ->
                                    new GitProperties(
                                            "git",
                                            "unused",
                                            Path.of("unused"),
                                            Duration.ofSeconds(15),
                                            100000,
                                            100000,
                                            20,
                                            100,
                                            1000000,
                                            2097152))
                    .withBean(GitSessionResolver.class, () -> execution -> SESSION)
                    .withBean(
                            GitWorkspaceResolver.class,
                            () ->
                                    context ->
                                            new GitWorkspaceContext(
                                                    EXECUTION_A.equals(context.executionId())
                                                            ? A
                                                            : B,
                                                    "project",
                                                    "branch",
                                                    "refs/heads/feature"));

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.datagent.agentv2.git.tool")
    static class Scan {}

    @Test
    void routesSameSessionExecutionsToTheirFrozenWorkspaces() {
        runner.run(
                context -> {
                    var tool = context.getBean(GitFilesSearchTool.class);
                    tool.execute(json.readTree(SEARCH), invocation(EXECUTION_A));
                    tool.execute(json.readTree(SEARCH), invocation(B));
                    tool.execute(json.readTree(SEARCH), invocation(EXECUTION_A));
                    verify(workspaces, times(2)).search(A, EMPTY, QUERY);
                    verify(workspaces).search(B, EMPTY, QUERY);
                    verify(workspaces, never()).search(SESSION, EMPTY, QUERY);
                });
    }

    @Test
    void modelCannotOverrideFrozenTarget() {
        runner.run(
                context -> {
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(GitFilesSearchTool.class)
                                                    .execute(
                                                            json.readTree(INJECTED),
                                                            invocation(EXECUTION_A)))
                            .isInstanceOf(IllegalArgumentException.class);
                    verifyNoInteractions(workspaces);
                });
    }

    @Test
    void candidateKeepsSessionForCardButUsesExecutionWorkspace() {
        runner.run(
                context -> {
                    context.getBean(GitChangePrepareTool.class)
                            .execute(
                                    json.readTree(
                                            "{\"summary\":\"summary\",\"commitMessage\":\"message\",\"expectedRevision\":2}"),
                                    invocation(EXECUTION_A));
                    verify(changes)
                            .prepareWorkspace(
                                    A, SESSION, EXECUTION_A, "request", "summary", "message", 2);
                });
    }

    private AgentTool.InvocationContext invocation(String execution) {
        return new AgentTool.InvocationContext("tenant_local", execution, 1, "call", "request");
    }
}
