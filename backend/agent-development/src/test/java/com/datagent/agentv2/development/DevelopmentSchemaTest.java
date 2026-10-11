package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.development.application.DevelopmentBuildService;
import com.datagent.agentv2.development.application.DevelopmentContextService;
import com.datagent.agentv2.development.application.DevelopmentOperations;
import com.datagent.agentv2.development.model.DevelopmentContracts.*;
import com.datagent.agentv2.development.model.DevelopmentContracts.DevelopmentConfig;
import com.datagent.agentv2.persistence.entity.development.DevelopmentOperationEntity;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.development.DevelopmentContextPersistenceService;
import com.datagent.agentv2.persistence.service.development.DevelopmentOperationPersistenceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** schema 必须显式配置，同时保留旧配置读取和补全能力。 */
class DevelopmentSchemaTest {
    private static final String PROJECT = "project";
    private static final String LEGACY_CONFIG =
            """
            {"repository":"repo","previewBinding":"preview","previewConfigVersion":"1",
             "productionBinding":"production","productionConfigVersion":"1","version":2}
            """;
    private static final String CONFIG =
            """
            {"repository":"repo","previewBinding":"preview","previewConfigVersion":"1",
             "previewSchemaName":"preview_metrics","productionBinding":"production",
             "productionConfigVersion":"1","productionSchemaName":"production_metrics","version":2}
            """;
    private static final String PREVIEW_SCHEMA = "previewSchemaName";
    private static final String PRODUCTION_SCHEMA = "productionSchemaName";
    private static final String PREVIEW_VALUE = "preview_metrics";
    private static final String PRODUCTION_VALUE = "production_metrics";
    private static final String SESSION = "session",
            BRANCH = "feature/schema",
            SHA = "a".repeat(40);

    @Test
    void legacyConfigRejectsNewPreviewBeforePersistingOperation() {
        var json = JsonMapper.builder().build();
        var config = json.readValue(LEGACY_CONFIG, DevelopmentConfig.class);
        var records = mock(DevelopmentOperationPersistenceService.class);
        var operations =
                new DevelopmentOperations(mock(DevelopmentContextService.class), records, json);
        var context =
                new DevelopmentContext(SESSION, PROJECT, config.repository(), BRANCH, SHA, config);
        assertThatThrownBy(
                        () ->
                                operations.acceptFrozen(
                                        context,
                                        OperationType.PREVIEW,
                                        PROJECT,
                                        new PreviewRequest(PROJECT, 0, null),
                                        Phase.SAVING))
                .hasMessageContaining("previewSchemaName");
        verifyNoInteractions(records);
    }

    @Test
    void legacyUnsubmittedOperationFailsWithActionableSchemaDiagnostic() throws Exception {
        var json = JsonMapper.builder().build();
        var config = json.readValue(LEGACY_CONFIG, DevelopmentConfig.class);
        var contexts = mock(DevelopmentContextService.class);
        var records = mock(DevelopmentOperationPersistenceService.class);
        var operations = new DevelopmentOperations(contexts, records, json);
        var row = new DevelopmentOperationEntity();
        row.setOperationId(UUID.randomUUID().toString());
        row.setType(OperationType.PREVIEW.name());
        row.setPhase(Phase.BUILD_SUBMITTING.name());
        row.setVersion(0L);
        row.setContextJson(
                json.writeValueAsString(
                        new DevelopmentContext(
                                SESSION, PROJECT, config.repository(), BRANCH, SHA, config)));
        when(records.find(row.getOperationId())).thenReturn(row);
        var client = mock(DbtServiceClient.class);
        var result =
                new DevelopmentBuildService(
                                operations,
                                records,
                                mock(DevelopmentContextPersistenceService.class),
                                client)
                        .reconcile(row, Environment.PREVIEW);
        assertThat(result.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(result.error().message())
                .contains("previewSchemaName", "productionSchemaName", "新操作");
        assertThat(result.error().retryable()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void legacySnapshotContinuesObservingAlreadyAcceptedBuild() throws Exception {
        var config = JsonMapper.builder().build().readValue(LEGACY_CONFIG, DevelopmentConfig.class);
        var operations = mock(DevelopmentOperations.class);
        var client = mock(DbtServiceClient.class);
        var row = new DevelopmentOperationEntity();
        var buildId = UUID.randomUUID();
        row.setBuildId(buildId.toString());
        row.setPushedSha(SHA);
        when(operations.context(row))
                .thenReturn(
                        new DevelopmentContext(
                                SESSION, PROJECT, config.repository(), BRANCH, SHA, config));
        var build = mock(BuildView.class);
        when(build.buildId()).thenReturn(buildId);
        when(build.buildStatus()).thenReturn(BuildStatus.RUNNING);
        when(build.repository()).thenReturn(config.repository());
        when(build.branchName()).thenReturn(BRANCH);
        when(build.requestedCommitSha()).thenReturn(SHA);
        when(build.environment()).thenReturn(Environment.PREVIEW);
        when(build.executionBinding()).thenReturn(config.previewBinding());
        when(build.configVersion()).thenReturn(config.previewConfigVersion());
        when(client.getBuild(buildId)).thenReturn(build);
        new DevelopmentBuildService(
                        operations,
                        mock(DevelopmentOperationPersistenceService.class),
                        mock(DevelopmentContextPersistenceService.class),
                        client)
                .reconcile(row, Environment.PREVIEW);
        verify(client).getBuild(buildId);
        verify(client, never()).submitBuild(any());
        verify(operations).update(row, Phase.BUILD_RUNNING, OperationStatus.RUNNING, null);
        verify(operations, never()).fail(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MixedCase", "1schema", "bad-name", "bad.schema"})
    void rejectsSchemaNamesThatDbtServiceCannotAccept(String schema) {
        var json = JsonMapper.builder().build();
        var input = (tools.jackson.databind.node.ObjectNode) json.readTree(CONFIG);
        input.put(PREVIEW_SCHEMA, schema);
        assertThatThrownBy(() -> json.treeToValue(input, DevelopmentConfig.class))
                .hasMessageContaining("schema");
    }

    @Test
    void configurationVersionChangePreservesExplicitSchemas() {
        var json = JsonMapper.builder().build();
        var config = json.readValue(CONFIG, DevelopmentConfig.class).atVersion(3);
        var saved = json.valueToTree(config);
        assertThat(saved.path(PREVIEW_SCHEMA).asString()).isEqualTo(PREVIEW_VALUE);
        assertThat(saved.path(PRODUCTION_SCHEMA).asString()).isEqualTo(PRODUCTION_VALUE);
    }

    @Test
    void legacyConfigCanBeReadButCannotBeWrittenWithoutSchemas() {
        var json = JsonMapper.builder().build();
        var config = json.readValue(LEGACY_CONFIG, DevelopmentConfig.class);
        var records = mock(DevelopmentContextPersistenceService.class);
        var projects = mock(ConversationProjectService.class);
        var service =
                new DevelopmentContextService(
                        records, projects, mock(ConversationSessionService.class), json);
        assertThatThrownBy(() -> service.configure(PROJECT, config, 2))
                .hasMessageContaining("schema");
        verifyNoInteractions(records, projects);
    }
}
