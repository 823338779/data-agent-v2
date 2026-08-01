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
    void baselineOpenApiHasNoOperationsBeforeTheRestContractIsAdded() throws IOException {
        assertThat(openApiOperationIds()).isEmpty();
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
