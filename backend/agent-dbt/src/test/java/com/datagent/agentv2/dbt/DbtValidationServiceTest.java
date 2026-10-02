package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.application.DbtValidationService;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.model.GitDraftSnapshot;
import com.datagent.agentv2.persistence.entity.dbt.DbtValidationEntity;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 网络结果未知时仍复用同一持久尝试；模型不能指定别的会话凭据。 */
class DbtValidationServiceTest {
    @Test
    void timeoutRetriesSamePersistedAttempt() throws Exception {
        var workspaces = mock(GitWorkspaceService.class);
        var records = mock(DbtValidationPersistenceService.class);
        var client = mock(DbtServiceClient.class);
        var json = new JsonMapper();
        var snapshot =
                new GitDraftSnapshot(
                        "session",
                        "workspace",
                        "a".repeat(40),
                        "b".repeat(40),
                        1,
                        List.of(
                                new GitDraftSnapshot.File(
                                        "A", "models/a.yml", null, "version: 2\n")));
        when(workspaces.freezeDraft("session", 1)).thenReturn(snapshot);
        var record = new DbtValidationEntity();
        record.setRecordId("record");
        record.setAttempt(0L);
        record.setState("UNKNOWN");
        when(records.reserve(any())).thenReturn(record);
        when(client.project())
                .thenReturn(
                        json.readTree(
                                "{\"projectSubdir\":\".\",\"capabilities\":[\"draft-validation-v1\"],\"protocolVersion\":\"agent-dbt-v1\"}"));
        var keys = new java.util.ArrayList<String>();
        when(client.submitValidation(any()))
                .thenAnswer(
                        call -> {
                            keys.add(
                                    ((tools.jackson.databind.JsonNode) call.getArgument(0))
                                            .path("idempotencyKey")
                                            .asString());
                            throw new java.io.IOException("lost receipt");
                        });
        var settings =
                new DbtProperties(
                        true,
                        URI.create("http://localhost:8000"),
                        "project",
                        ".",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20));
        var service = new DbtValidationService(workspaces, records, client, settings, json);
        assertThatThrownBy(() -> service.submit("session", 1))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> service.submit("session", 1))
                .isInstanceOf(java.io.IOException.class);
        assertThat(keys)
                .containsExactly("agent-dbt:validation:record:0", "agent-dbt:validation:record:0");
    }
}
