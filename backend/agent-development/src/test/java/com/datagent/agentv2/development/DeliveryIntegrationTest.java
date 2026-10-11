package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.application.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentOverview.DeliveryScope;
import com.datagent.agentv2.git.model.GitException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 真Git/数据库固定实际合并M，覆盖丢失响应和发布失败后显式重试。 */
class DeliveryIntegrationTest extends PreviewBuildIntegrationTest {
    String savedSession() {
        String s = session();
        edit(s, "macros/deliver.sql", "select 42");
        var op = saves.start(s, new SaveRequest("save", 1, "prepare delivery"));
        assertThat(saves.reconcile(op.operationId()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        return s;
    }

    DeliverRequest confirmation(String s) {
        var scope =
                DeliveryScope.compose(
                        context.forNewOperation(s),
                        git.describeBranch(repository, "feature/shared"));
        return new DeliverRequest(
                "deliver",
                scope.expectedSourceSha(),
                scope.expectedMainSha(),
                scope.confirmedScopeDigest());
    }

    @Test
    void changedSourceRequiresNewConfirmation() {
        String s = savedSession();
        var request = confirmation(s);
        String other = session();
        edit(other, "seeds/other.csv", "id\n2");
        var save = saves.start(other, new SaveRequest("save", 1, "other"));
        saves.reconcile(save.operationId());
        var client = mock(DbtServiceClient.class);
        var delivery =
                new DeliveryService(
                        operations,
                        context,
                        files,
                        git,
                        new DevelopmentBuildService(operations, records, contexts, client));
        assertThatThrownBy(() -> delivery.start(s, request)).hasMessageContaining("重新确认");
        verifyNoInteractions(client);
    }

    @Test
    void lostMergeResponseResumesOriginalCandidateWithoutMergingAgain() throws Exception {
        String s = savedSession();
        var client = mock(DbtServiceClient.class);
        var observed = spy(git);
        when(client.submitBuild(any()))
                .thenAnswer(
                        call ->
                                buildView(
                                        UUID.randomUUID(),
                                        call.getArgument(0),
                                        "SUCCEEDED",
                                        "DEPLOYED"));
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new GitException("TEST_RESPONSE_LOST", "response lost");
                        })
                .when(observed)
                .pushMerge(any());
        var delivery =
                new DeliveryService(
                        operations,
                        context,
                        files,
                        observed,
                        new DevelopmentBuildService(operations, records, contexts, client));
        var accepted = delivery.start(s, confirmation(s));
        assertThat(delivery.reconcile(accepted.operationId()).status())
                .isEqualTo(OperationStatus.RESULT_UNKNOWN);
        String candidate = records.find(accepted.operationId().toString()).getCandidateSha();
        var result = delivery.reconcile(accepted.operationId());
        assertThat(result.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(result.mergeSha()).isEqualTo(candidate);
        verify(observed, times(1)).prepareMerge(any(), any(), any(), any());
        verify(observed, times(1)).pushMerge(any());
        var captured = ArgumentCaptor.forClass(BuildRequest.class);
        verify(client).submitBuild(captured.capture());
        assertThat(captured.getValue().commitSha()).isEqualTo(candidate);
        assertThat(captured.getValue().environment()).isEqualTo(Environment.PRODUCTION);
        assertThat(captured.getValue().schemaName()).isEqualTo(PRODUCTION_SCHEMA);
        assertThat(captured.getValue().branchName()).isEqualTo("main");
    }

    @Test
    void failedReleaseRetriesBuildAtSameMergeShaOnly() throws Exception {
        String s = savedSession();
        var client = mock(DbtServiceClient.class);
        var observed = spy(git);
        when(client.submitBuild(any()))
                .thenAnswer(
                        call ->
                                buildView(
                                        UUID.randomUUID(), call.getArgument(0), "FAILED", "FAILED"))
                .thenAnswer(
                        call ->
                                buildView(
                                        UUID.randomUUID(),
                                        call.getArgument(0),
                                        "SUCCEEDED",
                                        "DEPLOYED"));
        var delivery =
                new DeliveryService(
                        operations,
                        context,
                        files,
                        observed,
                        new DevelopmentBuildService(operations, records, contexts, client));
        var accepted = delivery.start(s, confirmation(s));
        var failed = delivery.reconcile(accepted.operationId());
        assertThat(failed.status()).isEqualTo(OperationStatus.FAILED);
        records.recover(
                failed.operationId().toString(),
                failed.version(),
                "retry-release",
                "RELEASE_SUBMITTING");
        var result = delivery.reconcile(accepted.operationId());
        assertThat(result.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(result.mergeSha()).isEqualTo(failed.mergeSha());
        assertThat(result.buildIds()).hasSize(2);
        var requests = ArgumentCaptor.forClass(BuildRequest.class);
        verify(client, times(2)).submitBuild(requests.capture());
        assertThat(requests.getAllValues())
                .extracting(BuildRequest::commitSha)
                .containsOnly(failed.mergeSha());
        assertThat(requests.getAllValues())
                .extracting(BuildRequest::idempotencyKey)
                .doesNotHaveDuplicates();
        verify(observed, times(1)).prepareMerge(any(), any(), any(), any());
    }
}
