package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.application.*;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.entity.dbt.DbtValidationEntity;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DbtBranchValidationTest {
    @Test
    void missingBranchCapabilityFailsBeforePersistingOrSubmitting() throws Exception {
        var json = new ObjectMapper();
        var workspaces = mock(GitWorkspaceService.class);
        var records = mock(DbtValidationPersistenceService.class);
        var client = mock(DbtServiceClient.class);
        var context = new GitWorkspaceContext("w", "metrics", "b", "refs/heads/dev");
        when(workspaces.freezeDraft("w", 1))
                .thenReturn(
                        new GitDraftSnapshot(
                                "s",
                                "w",
                                "a".repeat(40),
                                "b".repeat(40),
                                1,
                                List.of(
                                        new GitDraftSnapshot.File(
                                                "A", "models/a.sql", null, "select 1")),
                                "metrics",
                                "b",
                                "refs/heads/dev"));
        when(client.project("b"))
                .thenReturn(
                        json.readTree(
                                "{\"protocolVersion\":\"agent-dbt-v1\",\"projectSubdir\":\".\",\"branchId\":\"b\",\"configVersion\":\"v1\",\"toolchainVersion\":\"t1\",\"capabilities\":[\"draft-validation-v2\"]}"));
        var props =
                new DbtProperties(
                        true,
                        URI.create("http://localhost"),
                        "metrics",
                        ".",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2));
        assertThatThrownBy(
                        () ->
                                new DbtValidationService(workspaces, records, client, props, json)
                                        .submit(context, "s", 1))
                .isInstanceOfSatisfying(
                        com.datagent.agentv2.conversation.exception.ToolBusinessException.class,
                        failure ->
                                assertThat(failure.code()).isEqualTo("DBT_CAPABILITY_UNSUPPORTED"));
        verifyNoInteractions(records);
        verify(client, never()).submitValidation(anyString(), any());
    }

    @Test
    void configurationChangeInvalidatesOtherwiseMatchingEvidence() throws Exception {
        var json = new ObjectMapper();
        var records = mock(DbtValidationPersistenceService.class);
        var client = mock(DbtServiceClient.class);
        var props =
                new DbtProperties(
                        true,
                        URI.create("http://localhost"),
                        "metrics",
                        ".",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2));
        var git =
                new GitDraftSnapshot(
                        "session",
                        "workspace",
                        "a".repeat(40),
                        "b".repeat(40),
                        3,
                        List.of(new GitDraftSnapshot.File("A", "models/a.sql", null, "select 1\n")),
                        "metrics",
                        "branch",
                        "refs/heads/dev");
        var draft = com.datagent.agentv2.dbt.model.DbtDraftSnapshot.composeFromGit(git, props);
        var row = new DbtValidationEntity();
        row.setRecordId("record");
        row.setValidationId("validation");
        row.setState("SUCCEEDED");
        row.setConversationSessionId("session");
        row.setWorkspaceId("workspace");
        row.setBranchId("branch");
        row.setGitRef("refs/heads/dev");
        row.setProjectId("metrics");
        row.setProjectSubdir(".");
        row.setBaseCommitSha(git.baseCommitSha());
        row.setTreeId(git.treeId());
        row.setDraftRevision(3L);
        row.setChangesDigest(draft.changesDigest());
        row.setConfigVersion("v1");
        row.setToolchainVersion("t1");
        var result =
                json.createObjectNode()
                        .put("state", "SUCCEEDED")
                        .put("valid", true)
                        .put("validationId", "validation")
                        .put("branchId", "branch")
                        .put("workspaceId", "workspace")
                        .put("draftRevision", 3)
                        .put("baseCommitSha", git.baseCommitSha())
                        .put("changesDigest", draft.changesDigest())
                        .put("projectSubdir", ".")
                        .put("configVersion", "v1")
                        .put("toolchainVersion", "t1")
                        .put("completedAt", "2026-10-04T00:00:00Z");
        result.putArray("checkedLevels")
                .add("YAML")
                .add("TEMPLATE")
                .add("DBT_PARSE")
                .add("SEMANTIC")
                .add("SQL_DEFINITION");
        row.setResultJson(json.writeValueAsString(result));
        when(records.latestBranchSnapshot(
                        "session", "workspace", "branch", git.baseCommitSha(), git.treeId(), 3))
                .thenReturn(row);
        var caps =
                json.createObjectNode()
                        .put("branchId", "branch")
                        .put("configVersion", "v1")
                        .put("toolchainVersion", "t1");
        when(client.project("branch")).thenReturn(caps);
        var gate = new DbtCandidateValidation(records, props, json, client);
        var evidence = gate.validate("session", git);
        assertThat(evidence.configVersion()).isEqualTo("v1");
        verify(client, never()).project(anyString());
        gate.checkCurrent(evidence);
        caps.put("configVersion", "v2");
        assertThatThrownBy(() -> gate.checkCurrent(evidence)).isInstanceOf(GitException.class);
    }

    @Test
    void validationPinsWorkspaceBranchRevisionAndRetriesSameAttempt() throws Exception {
        var json = new ObjectMapper();
        var workspaces = mock(GitWorkspaceService.class);
        var records = mock(DbtValidationPersistenceService.class);
        var client = mock(DbtServiceClient.class);
        var context = new GitWorkspaceContext("workspace", "metrics", "branch", "refs/heads/dev");
        var draft =
                new GitDraftSnapshot(
                        "creator",
                        "workspace",
                        "a".repeat(40),
                        "b".repeat(40),
                        3,
                        List.of(new GitDraftSnapshot.File("A", "models/a.sql", null, "select 1\n")),
                        "metrics",
                        "branch",
                        "refs/heads/dev");
        when(workspaces.freezeDraft("workspace", 3)).thenReturn(draft);
        var caps =
                json.readTree(
                        "{\"protocolVersion\":\"agent-dbt-v1\",\"projectSubdir\":\".\",\"branchId\":\"branch\",\"configVersion\":\"v1\",\"toolchainVersion\":\"t1\",\"capabilities\":[\"draft-validation-v2\",\"branch-development-v1\"]}");
        when(client.project("branch")).thenReturn(caps);
        var row = new DbtValidationEntity();
        row.setRecordId("record");
        row.setAttempt(0L);
        when(records.reserve(any())).thenReturn(row);
        when(client.submitValidation(eq("branch"), any()))
                .thenThrow(new java.io.IOException("lost receipt"));
        var props =
                new DbtProperties(
                        true,
                        URI.create("http://localhost"),
                        "metrics",
                        ".",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2));
        var service = new DbtValidationService(workspaces, records, client, props, json);
        assertThatThrownBy(() -> service.submit(context, "other-session", 3))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> service.submit(context, "other-session", 3))
                .isInstanceOf(java.io.IOException.class);
        var request = ArgumentCaptor.forClass(JsonNode.class);
        verify(client, times(2)).submitValidation(eq("branch"), request.capture());
        assertThat(request.getValue().path("workspaceId").asString()).isEqualTo("workspace");
        assertThat(request.getValue().path("draftRevision").asLong()).isEqualTo(3);
        assertThat(request.getAllValues().getFirst()).isEqualTo(request.getValue());
        var record = ArgumentCaptor.forClass(DbtValidationEntity.class);
        verify(records, times(2)).reserve(record.capture());
        assertThat(record.getValue().getConversationSessionId()).isEqualTo("other-session");
        assertThat(record.getValue().getBranchId()).isEqualTo("branch");
        verify(client, never()).submitValidation(any());
    }
}
