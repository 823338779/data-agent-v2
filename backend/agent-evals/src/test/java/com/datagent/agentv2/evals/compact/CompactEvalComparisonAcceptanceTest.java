package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 在两次机制运行结束后离线生成对比报告，不要求两个服务实例同时在线。 */
@EnabledIfSystemProperty(named = "data.agent.compact-eval.compare", matches = "true")
class CompactEvalComparisonAcceptanceTest {

    private static final String LEFT_PROPERTY = "data.agent.compact-eval.left";
    private static final String RIGHT_PROPERTY = "data.agent.compact-eval.right";
    private static final String OUTPUT_PROPERTY = "data.agent.compact-eval.comparison-output";
    private static final String DEFAULT_OUTPUT = "target/compact-eval/comparison.json";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void comparesTwoIndependentRunArtifacts() throws Exception {
        CompactEvalRunArtifact left = read(requiredProperty(LEFT_PROPERTY));
        CompactEvalRunArtifact right = read(requiredProperty(RIGHT_PROPERTY));
        CompactEvalComparison.ComparisonArtifact comparison =
                new CompactEvalComparison().compare(left, right);

        // 比较报告和单机制产物分离，服务无需为对比阶段保持运行。
        Path output = Path.of(System.getProperty(OUTPUT_PROPERTY, DEFAULT_OUTPUT));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), comparison);
        assertFalse(comparison.probes().isEmpty());
    }

    private CompactEvalRunArtifact read(String path) throws Exception {
        return json.readValue(Path.of(path).toFile(), CompactEvalRunArtifact.class);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value;
    }
}
