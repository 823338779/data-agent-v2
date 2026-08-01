package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContractExamplesTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    void openApiIsValidYaml() throws IOException {
        JsonNode api = YAML.readTree(ContractFiles.openApi().toFile());

        assertThat(api).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "auth-login-failed.yaml",
                "auth-session.yaml",
                "session-create.yaml",
                "session-running.yaml",
                "problem-validation.yaml"
            })
    void restExampleIsValidYamlAndDeclaresItsSchema(String file) throws IOException {
        JsonNode example =
                YAML.readTree(
                        ContractFiles.root().resolve("examples/rest").resolve(file).toFile());

        assertThat(example.path("schema").asText()).isNotBlank();
        assertThat(example.path("value").isMissingNode()).isFalse();
    }
}
