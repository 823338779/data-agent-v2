package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.application.PreviewBuildService;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

/** 使用真实保存前置，模拟引擎已受理但 HTTP 响应丢失。 */
class PreviewBuildIntegrationTest extends SourceSaveIntegrationTest {
    private static final String DRAFT_PATH = "models/next.sql",
            FIRST_CONTENT = "select 1",
            NEXT_CONTENT = "select 2",
            PREVIEW_KEY = "preview",
            SAVE_MESSAGE = "save preview",
            RUNNING_BUILD = "RUNNING",
            DEPLOYED = "DEPLOYED",
            SUCCEEDED_BUILD = "SUCCEEDED",
            DIRTY_STATE = "DIRTY",
            INVALID_REQUEST = "INVALID_REQUEST";

    @Test
    void acceptedPreviewAllowsNextEditWithoutChangingBuildInput() throws Exception {
        String s = session();
        edit(s, DRAFT_PATH, FIRST_CONTENT);
        var client = mock(DbtServiceClient.class);
        UUID build = UUID.randomUUID();
        when(client.submitBuild(any()))
                .thenAnswer(call -> buildView(build, call.getArgument(0), RUNNING_BUILD, DEPLOYED));
        var preview =
                new PreviewBuildService(
                        operations,
                        files,
                        saves,
                        new com.datagent.agentv2.development.application.DevelopmentBuildService(
                                operations, records, contexts, client));
        var accepted = preview.start(s, new PreviewRequest(PREVIEW_KEY, 1, SAVE_MESSAGE));
        var running = preview.reconcile(accepted.operationId());
        assertThat(running.status()).isEqualTo(OperationStatus.RUNNING);
        assertThat(running.buildIds()).containsExactly(build);
        var submitted = ArgumentCaptor.forClass(BuildRequest.class);
        verify(client).submitBuild(submitted.capture());
        assertThat(submitted.getValue().execution().schema()).isEqualTo(PREVIEW_SCHEMA);
        // 已受理构建持有固定SHA，下一批编辑不参与原构建，也不被完成回写覆盖。
        var current = files.read(s, DRAFT_PATH);
        assertThat(
                        files.apply(
                                s,
                                1,
                                java.util.List.of(
                                        new com.datagent.agentv2.git.model.FileOperation(
                                                com.datagent.agentv2.git.model.FileOperation.Type
                                                        .UPDATE,
                                                DRAFT_PATH,
                                                current.sha256(),
                                                NEXT_CONTENT))))
                .isEqualTo(2);
        when(client.getBuild(build))
                .thenReturn(buildView(build, submitted.getValue(), SUCCEEDED_BUILD, DEPLOYED));
        var complete = preview.reconcile(accepted.operationId());
        assertThat(complete.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(complete.pushedSha()).isEqualTo(submitted.getValue().commitSha());
        assertThat(contexts.session(s).getLocalState()).isEqualTo(DIRTY_STATE);
        assertThat(contexts.session(s).getRevision()).isEqualTo(2);
        assertThat(files.read(s, DRAFT_PATH).content()).isEqualTo(NEXT_CONTENT);
        verify(client, times(1)).submitBuild(any());
    }

    @Test
    void lostBuildResponseRestoresSameBuildId() throws Exception {
        String s = session();
        edit(s, "models/new.sql", "select 1");
        var client = mock(DbtServiceClient.class);
        UUID build = UUID.randomUUID();
        var preview =
                new PreviewBuildService(
                        operations,
                        files,
                        saves,
                        new com.datagent.agentv2.development.application.DevelopmentBuildService(
                                operations, records, contexts, client));
        var accepted = preview.start(s, new PreviewRequest("preview", 1, "save"));
        when(client.submitBuild(any()))
                .thenThrow(new java.io.IOException("response lost after acceptance"))
                .thenAnswer(call -> buildView(build, call.getArgument(0), "SUCCEEDED", "DEPLOYED"));
        var unknown = preview.reconcile(accepted.operationId());
        assertThat(unknown.status()).isEqualTo(OperationStatus.RESULT_UNKNOWN);
        // 修改下一次操作可见的配置；当前操作恢复仍须使用数据库中已冻结的快照。
        var previous = context.forNewOperation(s);
        var updated =
                new com.datagent.agentv2.development.model.DevelopmentContracts.DevelopmentConfig(
                        previous.repository(),
                        previous.configSnapshot().productionExecution(),
                        previous.configSnapshot().previewExecution(),
                        previous.configSnapshot().version() + 1);
        when(context.forNewOperation(s))
                .thenReturn(
                        new com.datagent.agentv2.development.model.DevelopmentContracts
                                .DevelopmentContext(
                                previous.sessionId(),
                                previous.projectId(),
                                previous.repository(),
                                previous.branchName(),
                                previous.baselineSha(),
                                updated));
        // 模拟只保留数据库、无工具/SSE 对象的应用重启；原请求键和已推送 SHA 不变。
        preview =
                new PreviewBuildService(
                        operations,
                        files,
                        saves,
                        new com.datagent.agentv2.development.application.DevelopmentBuildService(
                                operations, records, contexts, client));
        var result = preview.reconcile(accepted.operationId());
        assertThat(result.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(result.operationId()).isEqualTo(accepted.operationId());
        assertThat(result.buildIds()).containsExactly(build);
        var requests = ArgumentCaptor.forClass(BuildRequest.class);
        verify(client, times(2)).submitBuild(requests.capture());
        assertThat(requests.getAllValues().get(0)).isEqualTo(requests.getAllValues().get(1));
        assertThat(requests.getValue().commitSha()).isEqualTo(result.pushedSha());
        assertThat(requests.getValue().execution())
                .isEqualTo(ExecutionFixtures.config().previewExecution());
        assertThat(requests.getValue().execution().schema()).isEqualTo(PREVIEW_SCHEMA);
        assertThat(requests.getValue().deploymentPolicy()).isEqualTo(DeploymentPolicy.ON_SUCCESS);
    }

    @Test
    void successfulBuildWithStaleDeploymentIsBlocked() throws Exception {
        String s = session();
        var client = mock(DbtServiceClient.class);
        UUID build = UUID.randomUUID();
        when(client.submitBuild(any()))
                .thenAnswer(call -> buildView(build, call.getArgument(0), "SUCCEEDED", "STALE"));
        var preview =
                new PreviewBuildService(
                        operations,
                        files,
                        saves,
                        new com.datagent.agentv2.development.application.DevelopmentBuildService(
                                operations, records, contexts, client));
        var accepted = preview.start(s, new PreviewRequest("preview", 0, null));
        assertThat(preview.reconcile(accepted.operationId()).status())
                .isEqualTo(OperationStatus.BLOCKED);
    }

    @Test
    void rejectedBuildRequestIsTerminalInsteadOfUnknown() throws Exception {
        String session = session();
        var client = mock(DbtServiceClient.class);
        when(client.submitBuild(any()))
                .thenThrow(
                        new com.datagent.agentv2.conversation.exception.ToolBusinessException(
                                INVALID_REQUEST, "目标 schema 未被引擎接受"));
        var preview =
                new PreviewBuildService(
                        operations,
                        files,
                        saves,
                        new com.datagent.agentv2.development.application.DevelopmentBuildService(
                                operations, records, contexts, client));
        var accepted = preview.start(session, new PreviewRequest(PREVIEW_KEY, 0, null));
        var result = preview.reconcile(accepted.operationId());
        assertThat(result.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(result.error().code()).isEqualTo(INVALID_REQUEST);
        assertThat(result.buildIds()).isEmpty();
        preview.reconcile(accepted.operationId());
        verify(client, times(1)).submitBuild(any());
    }

    BuildView buildView(UUID id, BuildRequest request, String status, String deployment) {
        var body = json.valueToTree(request).deepCopy();
        var node = (tools.jackson.databind.node.ObjectNode) body;
        node.remove("deploymentPolicy");
        node.remove("idempotencyKey");
        node.remove("origin");
        node.put("buildId", id.toString());
        node.put("requestedCommitSha", request.commitSha());
        node.put("toolchainVersion", "test");
        node.put("buildStatus", status);
        node.put("phase", "SEALED");
        node.put("createdAt", "2026-10-10T00:00:00Z");
        node.put("updatedAt", "2026-10-10T00:00:00Z");
        node.put("catalogAvailable", true);
        node.put("queryAvailable", true);
        node.put("cancelRequested", false);
        var attempt = node.putObject("initialDeployment");
        attempt.put("repository", request.repository());
        attempt.put("branchName", request.branchName());
        attempt.put("environmentId", request.execution().environmentId().toString());
        attempt.put("generation", 1);
        attempt.put("buildId", id.toString());
        attempt.put("deploymentStatus", deployment);
        attempt.put("createdAt", "2026-10-10T00:00:00Z");
        try {
            return json.treeToValue(node, BuildView.class);
        } catch (Exception malformedFixture) {
            throw new AssertionError("Invalid build fixture", malformedFixture);
        }
    }
}
