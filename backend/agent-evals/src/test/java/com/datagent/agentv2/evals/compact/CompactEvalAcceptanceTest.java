package com.datagent.agentv2.evals.compact;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 针对当前已启动服务执行真实长对话并输出独立评测结果文档。 */
@EnabledIfSystemProperty(named = "data.agent.compact-eval.enabled", matches = "true")
class CompactEvalAcceptanceTest {

    private static final String CORPUS_PROPERTY = "data.agent.compact-eval.corpus";
    private static final String TARGET_PROPERTY = "data.agent.compact-eval.target";
    private static final String CONFIGURATION_PROPERTY = "data.agent.compact-eval.configuration";
    private static final String OUTPUT_PROPERTY = "data.agent.compact-eval.output";
    private static final String TURN_TIMEOUT_PROPERTY = "data.agent.compact-eval.turn-timeout";
    private static final String MAX_SCENARIOS_PROPERTY = "data.agent.compact-eval.max-scenarios";
    private static final String NEEDLE_CONTEXT_CHARACTERS_PROPERTY =
            "data.agent.compact-eval.needle-context-characters";
    private static final String JUDGE_EXECUTABLE_PROPERTY =
            "data.agent.compact-eval.judge.executable";
    private static final String JUDGE_MODEL_PROPERTY = "data.agent.compact-eval.judge.model";
    private static final String JUDGE_EFFORT_PROPERTY = "data.agent.compact-eval.judge.effort";
    private static final String JUDGE_TIMEOUT_PROPERTY = "data.agent.compact-eval.judge.timeout";
    private static final String DEFAULT_TARGET = "http://127.0.0.1:8080";
    private static final String DEFAULT_TURN_TIMEOUT = "PT10M";
    private static final String DEFAULT_CONFIGURATION = "current-server-config";
    private static final String DEFAULT_OUTPUT = "target/compact-eval/evaluation-result.json";
    private static final String DEFAULT_JUDGE_EXECUTABLE = "codex";
    private static final String DEFAULT_JUDGE_MODEL = "gpt-5.6-sol";
    private static final String DEFAULT_JUDGE_EFFORT = "medium";
    private static final String DEFAULT_JUDGE_TIMEOUT = "PT5M";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evaluatesCurrentServerConfigurationAndWritesReport() throws Exception {
        String configuration = System.getProperty(CONFIGURATION_PROPERTY, DEFAULT_CONFIGURATION);
        String target = System.getProperty(TARGET_PROPERTY, DEFAULT_TARGET);
        Path corpusPath = Path.of(requiredProperty(CORPUS_PROPERTY));
        Path outputPath = Path.of(System.getProperty(OUTPUT_PROPERTY, DEFAULT_OUTPUT));
        Duration turnTimeout =
                Duration.parse(System.getProperty(TURN_TIMEOUT_PROPERTY, DEFAULT_TURN_TIMEOUT));
        int maxScenarios = Integer.parseInt(System.getProperty(MAX_SCENARIOS_PROPERTY, "0"));
        int needleContextCharacters =
                Integer.parseInt(System.getProperty(NEEDLE_CONTEXT_CHARACTERS_PROPERTY, "32000"));

        // 所有输入只经过公开 HTTP 接口，Compact 私有实现和持久化均不被评测端读取。
        CompactEvalCorpus corpus =
                new CompactEvalCorpusLoader(json)
                        .load(corpusPath, maxScenarios, needleContextCharacters);
        CompactEvalGateway gateway = new HttpCompactEvalGateway(URI.create(target), json);
        CompactEvalJudge judge =
                new CodexCliCompactEvalJudge(
                        System.getProperty(JUDGE_EXECUTABLE_PROPERTY, DEFAULT_JUDGE_EXECUTABLE),
                        System.getProperty(JUDGE_MODEL_PROPERTY, DEFAULT_JUDGE_MODEL),
                        System.getProperty(JUDGE_EFFORT_PROPERTY, DEFAULT_JUDGE_EFFORT),
                        Duration.parse(
                                System.getProperty(JUDGE_TIMEOUT_PROPERTY, DEFAULT_JUDGE_TIMEOUT)),
                        json);
        CompactEvalReport report =
                new CompactEvalRunner()
                        .run(
                                configuration,
                                target,
                                corpus,
                                gateway,
                                judge,
                                turnTimeout,
                                maxScenarios);

        // 产物写入 target 或显式路径，避免把本地执行结果误提交到仓库。
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        json.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
        assertFalse(report.scenarios().isEmpty());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value;
    }
}
