package com.datagent.agentv2.development;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.development.model.DevelopmentContracts.DevelopmentConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 项目只保存完整快照，旧字段与缺失目标均在受理前拒绝。 */
class DevelopmentSchemaTest {
    private static final String PREVIEW = "previewExecution", PRODUCTION = "productionExecution";
    private static final String SCHEMA = "schema", LEGACY = "previewBinding";
    private final JsonMapper json = new JsonMapper();

    @Test
    void fullConfigurationRoundTripsWithoutLosingTargets() {
        var config = json.readValue(ExecutionFixtures.CONFIG, DevelopmentConfig.class);
        assertThat(json.readTree(json.writeValueAsString(config)))
                .isEqualTo(json.readTree(ExecutionFixtures.CONFIG));
    }

    @ParameterizedTest
    @ValueSource(strings = {PREVIEW, PRODUCTION})
    void missingExecutionCannotBeReadOrAccepted(String field) {
        var node = (ObjectNode) json.readTree(ExecutionFixtures.CONFIG);
        node.remove(field);
        assertThatThrownBy(() -> json.treeToValue(node, DevelopmentConfig.class))
                .hasMessageContaining("完整执行快照");
    }

    @Test
    void legacyFieldsAreRejectedEvenWithCompleteSnapshot() {
        var node = (ObjectNode) json.readTree(ExecutionFixtures.CONFIG);
        node.put(LEGACY, LEGACY);
        assertThatThrownBy(() -> json.treeToValue(node, DevelopmentConfig.class))
                .hasMessageContaining("未知字段");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MixedCase", "1schema", "bad-name", "bad.schema"})
    void rejectsSchemaNamesThatDbtServiceCannotAccept(String schema) {
        var input = (ObjectNode) json.readTree(ExecutionFixtures.CONFIG);
        ((ObjectNode) input.path(PREVIEW)).put(SCHEMA, schema);
        assertThatThrownBy(() -> json.treeToValue(input, DevelopmentConfig.class))
                .hasMessageContaining("执行快照");
    }

    @Test
    void configurationVersionChangePreservesFullExecutionSnapshots() {
        var before = ExecutionFixtures.config();
        var after = before.atVersion(3);
        assertThat(after.version()).isEqualTo(3);
        assertThat(after.previewExecution()).isEqualTo(before.previewExecution());
        assertThat(after.productionExecution()).isEqualTo(before.productionExecution());
    }
}
