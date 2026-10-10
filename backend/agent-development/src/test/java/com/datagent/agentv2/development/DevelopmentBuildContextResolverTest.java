package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.application.DevelopmentBuildContextResolver;
import com.datagent.agentv2.persistence.entity.development.DevelopmentSessionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 分析只在首次选择时读取部署，未部署开发分支绝不借用普通会话配置。 */
class DevelopmentBuildContextResolverTest {
    private final DevelopmentContextPersistenceService records =
            mock(DevelopmentContextPersistenceService.class);
    private final AgentExecutionService executions = mock(AgentExecutionService.class);

    @BeforeEach
    void executionSelections() throws Exception {
        when(executions.selectAnalysisBuildIfAbsent(anyString(), anyString(), any()))
                .thenAnswer(call -> call.getArgument(2));
    }

    private final DbtServiceClient client = mock(DbtServiceClient.class);

    @Test
    void existingSelectionNeverRequeriesNewDeployment() throws Exception {
        var row = new DevelopmentSessionEntity();
        UUID build = UUID.randomUUID();
        row.setSelectedBuildId(build.toString());
        when(records.session("s")).thenReturn(row);
        assertThat(
                        new DevelopmentBuildContextResolver(records, executions, client, "")
                                .resolve("s", "execution"))
                .isEqualTo(build);
        verifyNoInteractions(client);
    }

    @Test
    void firstSelectionUsesAtomicWinnerAndPreviewNaturalKey() throws Exception {
        var row = new DevelopmentSessionEntity();
        row.setRepository("repo");
        row.setBranchName("feature/a");
        when(records.session("s")).thenReturn(row);
        UUID observed = UUID.randomUUID(), winner = UUID.randomUUID();
        when(client.currentDeployment(new DeploymentKey("repo", Environment.PREVIEW, "feature/a")))
                .thenReturn(
                        new DeploymentTargetView(
                                "repo",
                                Environment.PREVIEW,
                                "feature/a",
                                1,
                                1,
                                observed,
                                "sha",
                                null,
                                null,
                                null,
                                "ACTIVE"));
        when(records.selectBuildIfAbsent("s", observed)).thenReturn(winner);
        assertThat(
                        new DevelopmentBuildContextResolver(records, executions, client, "")
                                .resolve("s", "execution"))
                .isEqualTo(winner);
    }

    @Test
    void undeployedPreviewDoesNotFallBackToOrdinaryBuild() throws Exception {
        var row = new DevelopmentSessionEntity();
        row.setRepository("repo");
        row.setBranchName("feature/a");
        when(records.session("s")).thenReturn(row);
        when(client.currentDeployment(any()))
                .thenReturn(
                        new DeploymentTargetView(
                                "repo",
                                Environment.PREVIEW,
                                "feature/a",
                                0,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "UNDEPLOYED"));
        assertThatThrownBy(
                        () ->
                                new DevelopmentBuildContextResolver(
                                                records,
                                                executions,
                                                client,
                                                UUID.randomUUID().toString())
                                        .resolve("s", "execution"))
                .isInstanceOfSatisfying(
                        com.datagent.agentv2.conversation.exception.ToolBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("DBT_BUILD_NOT_SELECTED"));
        verify(records, never()).selectBuildIfAbsent(anyString(), any());
    }

    @Test
    void ordinarySessionUsesOnlyExplicitControlledBuild() throws Exception {
        UUID build = UUID.randomUUID();
        assertThat(
                        new DevelopmentBuildContextResolver(
                                        records, executions, client, build.toString())
                                .resolve("ordinary", "execution"))
                .isEqualTo(build);
        assertThatThrownBy(
                        () ->
                                new DevelopmentBuildContextResolver(records, executions, client, "")
                                        .resolve("ordinary", "execution"))
                .isInstanceOf(
                        com.datagent.agentv2.conversation.exception.ToolBusinessException.class);
        verifyNoInteractions(client);
    }

    @Test
    void previewCompletionOnlyChangesSubsequentExecutions() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var row = new DevelopmentSessionEntity();
        row.setSelectedBuildId(first.toString());
        when(records.session("s")).thenReturn(row);
        var pinned = new java.util.HashMap<String, UUID>();
        when(executions.analysisBuild(anyString(), eq("s")))
                .thenAnswer(call -> pinned.get(call.getArgument(0)));
        when(executions.selectAnalysisBuildIfAbsent(anyString(), eq("s"), any()))
                .thenAnswer(
                        call ->
                                pinned.computeIfAbsent(
                                        call.getArgument(0), key -> call.getArgument(2)));
        var resolver = new DevelopmentBuildContextResolver(records, executions, client, "");
        assertThat(resolver.resolve("s", "first-execution")).isEqualTo(first);
        row.setSelectedBuildId(second.toString());
        assertThat(resolver.resolve("s", "first-execution")).isEqualTo(first);
        assertThat(resolver.resolve("s", "next-execution")).isEqualTo(second);
        verifyNoInteractions(client);
    }
}
