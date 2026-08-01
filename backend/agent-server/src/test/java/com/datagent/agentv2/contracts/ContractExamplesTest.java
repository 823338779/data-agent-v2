package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ContractExamplesTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    void openApiIsValidYaml() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());

        assertThat(api).isNotNull();
    }
}
