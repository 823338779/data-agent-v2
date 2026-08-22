package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 针对单个已启动 Compact 机制执行真实长对话，A/B 必须在两次独立运行中完成。 */
@EnabledIfSystemProperty(named = "data.agent.compact-eval.enabled", matches = "true")
class CompactEvalAcceptanceTest {

    private static final String CORPUS_PROPERTY = "data.agent.compact-eval.corpus";
    private static final String TARGET_PROPERTY = "data.agent.compact-eval.target";
    private static final String MECHANISM_PROPERTY = "data.agent.compact-eval.mechanism";
    private static final String OUTPUT_PROPERTY = "data.agent.compact-eval.output";
    private static final String TURN_TIMEOUT_PROPERTY = "data.agent.compact-eval.turn-timeout";
    private static final String MAX_SCENARIOS_PROPERTY = "data.agent.compact-eval.max-scenarios";
    private static final String DEFAULT_TARGET = "http://127.0.0.1:8080";
    private static final String DEFAULT_TURN_TIMEOUT = "PT10M";
    private static final String DEFAULT_OUTPUT_PREFIX = "target/compact-eval/";
    private static final String JSON_SUFFIX = ".json";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replaysCorpusAndWritesSingleMechanismArtifact() throws Exception {
        String mechanism = requiredProperty(MECHANISM_PROPERTY);
        Path corpusPath = Path.of(requiredProperty(CORPUS_PROPERTY));
        Path outputPath =
                Path.of(
                        System.getProperty(
                                OUTPUT_PROPERTY,
                                DEFAULT_OUTPUT_PREFIX + mechanism + "-run" + JSON_SUFFIX));
        Duration turnTimeout =
                Duration.parse(System.getProperty(TURN_TIMEOUT_PROPERTY, DEFAULT_TURN_TIMEOUT));
        int maxScenarios = Integer.parseInt(System.getProperty(MAX_SCENARIOS_PROPERTY, "0"));

        // 所有输入只经过公开 HTTP 接口，Compact 私有实现和持久化均不被评测端读取。
        CompactEvalCorpus corpus = new CompactEvalCorpusLoader(json).load(corpusPath);
        CompactEvalGateway gateway =
                new HttpCompactEvalGateway(
                        URI.create(System.getProperty(TARGET_PROPERTY, DEFAULT_TARGET)), json);
        CompactEvalRunArtifact artifact =
                new CompactEvalRunner().run(mechanism, corpus, gateway, turnTimeout, maxScenarios);

        // 产物写入 target 或显式路径，避免把本地执行结果误提交到仓库。
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        json.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), artifact);
        assertFalse(artifact.scenarios().isEmpty());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value;
    }
}
