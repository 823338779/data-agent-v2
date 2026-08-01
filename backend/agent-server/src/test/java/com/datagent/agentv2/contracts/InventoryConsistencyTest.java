package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class InventoryConsistencyTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    void everySourceSymbolAndOperationIdAppearsExactlyOnce() throws IOException {
        JsonNode inventory = YAML.readTree(ContractFiles.inventory("source-surface.yaml").toFile());
        List<String> symbols = values(inventory, "entries", "sourceSymbol");
        List<String> operations = values(inventory, "entries", "operationId");

        assertThat(symbols).allMatch(value -> !value.isBlank()).doesNotHaveDuplicates();
        assertThat(operations).allMatch(value -> !value.isBlank()).doesNotHaveDuplicates();
        assertThat(operations).containsExactlyInAnyOrderElementsOf(openApiOperationIds());
    }

    @Test
    void everyFeatureDeclaresOutcomesSecurityAndMigrationPhase() throws IOException {
        JsonNode inventory = YAML.readTree(ContractFiles.inventory("features.yaml").toFile());

        assertThat(inventory.path("features")).isNotEmpty();
        inventory
                .path("features")
                .forEach(
                        feature -> {
                            assertThat(feature.path("entry").asText()).isNotBlank();
                            assertThat(feature.path("success").asText()).isNotBlank();
                            assertThat(feature.path("failure").asText()).isNotBlank();
                            assertThat(feature.path("security").asText()).isNotBlank();
                            assertThat(feature.path("phase").isIntegralNumber()).isTrue();
                        });
    }

    @Test
    void toolsAndSkillsAreCompleteAndCrossReferenced() throws IOException {
        JsonNode tools = YAML.readTree(ContractFiles.inventory("tools.yaml").toFile());
        JsonNode skills = YAML.readTree(ContractFiles.inventory("skills.yaml").toFile());

        assertThat(tools.path("tools")).hasSize(56);
        assertThat(skills.path("skills")).hasSize(8);
        assertThat(values(tools, "tools", "name")).doesNotHaveDuplicates();
        assertThat(values(skills, "skills", "name")).doesNotHaveDuplicates();
        assertThat(values(skills, "skills", "name"))
                .containsAll(valuesFlattened(tools, "tools", "allowedSkills"));
        tools.path("tools")
                .forEach(
                        tool -> {
                            assertThat(tool.path("name").asText()).isNotBlank();
                            assertThat(tool.path("description").asText()).isNotBlank();
                            assertThat(tool.path("inputSchema").isObject()).isTrue();
                            assertThat(tool.path("risk").asText()).isNotBlank();
                            assertThat(tool.path("idempotent").isBoolean()).isTrue();
                            assertThat(tool.path("requiredScopes").isArray()).isTrue();
                            assertThat(tool.path("allowedSkills").isArray()).isTrue();
                            assertThat(tool.path("interactionMode").asText()).isNotBlank();
                            assertThat(tool.path("resultCodes").isArray()).isTrue();
                            assertThat(tool.path("phase").isIntegralNumber()).isTrue();
                        });
        skills.path("skills")
                .forEach(
                        skill -> assertThat(skill.path("sha256").asText()).matches("[0-9a-f]{64}"));
    }

    @Test
    void legacyDecisionsResolveStreamingAndQueryScopeAmbiguity() throws IOException {
        JsonNode decisions =
                YAML.readTree(ContractFiles.inventory("legacy-decisions.yaml").toFile());

        assertThat(values(decisions, "decisions", "id"))
                .containsExactlyInAnyOrder("assistant-delta", "query-scope-interaction");
        assertThat(decisions.toString())
                .contains(
                        "phase-1-production-event",
                        "do-not-migrate-until-a-producing-path-is-proven");
    }

    private static List<String> values(JsonNode root, String array, String field) {
        return StreamSupport.stream(root.path(array).spliterator(), false)
                .map(node -> node.path(field).asText())
                .toList();
    }

    private static List<String> valuesFlattened(JsonNode root, String array, String field) {
        return StreamSupport.stream(root.path(array).spliterator(), false)
                .flatMap(node -> StreamSupport.stream(node.path(field).spliterator(), false))
                .map(JsonNode::asText)
                .distinct()
                .toList();
    }

    private static Set<String> openApiOperationIds() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());
        Set<String> ids = new HashSet<>();
        api.path("paths")
                .forEach(
                        path ->
                                path.forEach(
                                        operation -> {
                                            if (operation.hasNonNull("operationId")) {
                                                ids.add(operation.path("operationId").asText());
                                            }
                                        }));
        return ids;
    }
}
