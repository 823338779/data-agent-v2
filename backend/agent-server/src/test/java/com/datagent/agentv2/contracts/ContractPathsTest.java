package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractPathsTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    void resolvesContractsFromTheRepositoryRoot() {
        assertThat(ContractFiles.root()).endsWith(Path.of("data-agent-v2", "contracts"));
        assertThat(ContractFiles.openApi()).exists();
    }

    @Test
    void openApiDeclaresItsBaselineStructure() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());

        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.path("info").isObject()).isTrue();
        assertThat(api.path("paths").isObject()).isTrue();
        assertThat(api.path("components").path("schemas").isObject()).isTrue();
    }
}
