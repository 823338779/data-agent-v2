package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Draft202012;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitCardContractTest {
    final JsonMapper json = new JsonMapper();

    @Test
    void bothRestAndSseAcceptOnlyTypedCardPayloads() throws Exception {
        var git =
                json.readTree(
                        "{\"blockId\":\"b1\",\"type\":\"git-change-card\",\"schemaVersion\":1,\"variant\":\"default\",\"payload\":{\"changeId\":\"c1\",\"sessionId\":\"s1\"},\"displayOrder\":0}");
        var artifact =
                json.readTree(
                        "{\"blockId\":\"b2\",\"type\":\"artifact-card\",\"schemaVersion\":1,\"variant\":\"default\",\"payload\":{\"artifactId\":\"a1\"},\"displayOrder\":1}");
        var sse = json.createObjectNode();
        sse.set(
                "$defs",
                json.readTree(ContractFiles.schema("session-sse-event.schema.json")).path("$defs"));
        sse.put("$ref", "#/$defs/uiBlock");
        var api =
                json.readTree(
                        new YAMLMapper()
                                .readTree(ContractFiles.root().resolve("openapi.yaml").toFile())
                                .toString());
        var rest = json.createObjectNode();
        rest.set("components", api.path("components"));
        rest.put("$ref", "#/components/schemas/MessageUiBlockView");
        for (var document : java.util.List.of(sse, rest)) {
            var schema =
                    SchemaRegistry.withDefaultDialect(Draft202012.getInstance())
                            .getSchema(document);
            assertThat(schema.validate(git)).isEmpty();
            assertThat(schema.validate(artifact)).isEmpty();
            var malformed = git.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) malformed.path("payload"))
                    .remove("sessionId");
            assertThat(schema.validate(malformed)).isNotEmpty();
            var mixed = git.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) mixed.path("payload"))
                    .put("artifactId", "a1");
            assertThat(schema.validate(mixed)).isNotEmpty();
        }
    }
}
