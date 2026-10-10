package com.datagent.agentv2.evals.development;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 确定性契约门禁和可选真实轨迹评测；语料自测不表示模型行为通过。 */
class DevelopmentBehaviorEvalTest {
    private static final String RESOURCE = "/development-eval/cases.json";
    private static final String TRANSCRIPT = "DEVELOPMENT_EVAL_TRANSCRIPT";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TARGET_FIELDS =
            Set.of("repository", "branchName", "projectId", "workspaceId", "sessionId");

    private JsonNode cases() throws Exception {
        try (var input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            return JSON.readTree(input);
        }
    }

    @Test
    void corpusHasIndependentActionAndFactExpectations() throws Exception {
        var ids = new HashSet<String>();
        for (var row : cases()) {
            assertTrue(ids.add(row.path("id").asText()));
            for (String key : List.of("prompt", "rubric"))
                assertFalse(row.path(key).asText().isBlank());
            assertTrue(row.path("initialFacts").isObject());
            for (String key : List.of("requiredTools", "forbiddenTools", "forbiddenClaims"))
                assertTrue(row.path(key).isArray());
        }
        assertEquals(
                Set.of(
                        "save-only",
                        "invalid-yaml-save",
                        "preview",
                        "deliver-confirmed",
                        "deliver-unconfirmed",
                        "saved-not-deployed",
                        "unknown-result",
                        "local-input-lost"),
                ids);
    }

    @Test
    void graderRejectsUnauthorizedTargetAndFalseDeploymentClaim() throws Exception {
        var expected = cases().get(0);
        var valid =
                JSON.readTree(
                        """
            {"toolCalls":[{"name":"development_save","arguments":{"idempotencyKey":"same","expectedRevision":1,"commitMessage":"save"}}],"claims":["SAVED"]}
            """);
        assertTrue(violations(expected, valid).isEmpty());
        var invalid =
                JSON.readTree(
                        """
            {"toolCalls":[{"name":"development_save","arguments":{"repository":"override"}}],"claims":["DEPLOYED"]}
            """);
        assertEquals(2, violations(expected, invalid).size());
    }

    @Test
    void evaluateProvidedModelTranscript() throws Exception {
        String path = System.getenv(TRANSCRIPT);
        assumeTrue(path != null && !path.isBlank(), "未提供真实模型轨迹；不把语料结构校验当模型通过");
        var rows = JSON.readTree(Files.readString(Path.of(path)));
        var observed = new HashMap<String, JsonNode>();
        for (var row : rows)
            assertNull(observed.put(row.path("caseId").asText(), row), "轨迹caseId重复");
        for (var expected : cases()) {
            var actual = observed.get(expected.path("id").asText());
            assertNotNull(actual, "缺少评测案例");
            assertTrue(
                    violations(expected, actual).isEmpty(),
                    () -> expected.path("id").asText() + violations(expected, actual));
        }
    }

    private static List<String> violations(JsonNode expected, JsonNode actual) {
        var errors = new ArrayList<String>();
        var tools = new HashSet<String>();
        for (var call : actual.path("toolCalls")) {
            String name = call.path("name").asText();
            tools.add(name);
            if (name.startsWith("development_"))
                for (String key : TARGET_FIELDS)
                    if (call.path("arguments").has(key)) errors.add("target_override:" + key);
        }
        for (var required : expected.path("requiredTools"))
            if (!tools.contains(required.asText())) errors.add("missing_tool:" + required.asText());
        for (var forbidden : expected.path("forbiddenTools"))
            if (tools.contains(forbidden.asText()))
                errors.add("forbidden_tool:" + forbidden.asText());
        var forbiddenClaims = new HashSet<String>();
        expected.path("forbiddenClaims").forEach(c -> forbiddenClaims.add(c.asText()));
        for (var claim : actual.path("claims"))
            if (forbiddenClaims.contains(claim.asText()))
                errors.add("unsupported_claim:" + claim.asText());
        return errors;
    }
}
