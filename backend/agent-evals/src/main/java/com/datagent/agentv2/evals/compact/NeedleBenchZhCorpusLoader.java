package com.datagent.agentv2.evals.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 从 NeedleBench 中文推理链和中文 haystack 原文确定性生成长上下文场景。 */
final class NeedleBenchZhCorpusLoader {

    static final String NEEDLE_FILE = "multi_needle_reasoning_zh.json";
    static final int DEFAULT_CONTEXT_CHARACTERS = 32_000;

    private static final String SOURCE_PREFIX = "NeedleBench/zh/";
    private static final String HAYSTACK_GLOB = "zh_*.jsonl";

    private final ObjectMapper json;

    NeedleBenchZhCorpusLoader(ObjectMapper json) {
        this.json = json;
    }

    CompactEvalCorpus load(Path directory, int maxScenarios, int contextCharacters)
            throws IOException {
        if (contextCharacters < 1) {
            throw new IllegalArgumentException("NeedleBench context characters must be positive");
        }
        List<String> haystack = loadHaystack(directory);
        JsonNode needles = json.readTree(directory.resolve(NEEDLE_FILE).toFile());
        if (!needles.isArray() || needles.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedleBench reasoning file must be a non-empty array");
        }

        List<CompactEvalCorpus.Scenario> scenarios = new ArrayList<>();
        for (JsonNode item : needles) {
            if (maxScenarios > 0 && scenarios.size() >= maxScenarios) {
                break;
            }
            List<String> derivations = stringArray(item, "derivations");
            ContextBuild built =
                    buildContext(haystack, scenarios.size(), derivations, contextCharacters);
            String id = requiredText(item, "id");
            List<String> history =
                    CompactEvalTextSplitter.split(built.context(), "NeedleBench 中文长文档");
            scenarios.add(
                    new CompactEvalCorpus.Scenario(
                            id,
                            "NeedleBench 中文多针推理 - " + id,
                            new CompactEvalCorpus.ScenarioMetadata(
                                    "NeedleBench",
                                    "zh",
                                    "multi-needle-reasoning",
                                    requiredText(item, "type"),
                                    requiredText(item, "level"),
                                    false,
                                    null,
                                    null,
                                    null,
                                    history.size(),
                                    java.util.Map.of("characters", built.context().length()),
                                    built.evidence()),
                            history,
                            List.of(
                                    new CompactEvalCorpus.Probe(
                                            requiredText(item, "question"),
                                            requiredText(item, "answer")))));
        }
        return new CompactEvalCorpus(
                SOURCE_PREFIX + contextCharacters + "-characters", List.copyOf(scenarios));
    }

    private List<String> loadHaystack(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(directory)) {
            files =
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().matches("zh_.+\\.jsonl"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .toList();
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedleBench directory contains no " + HAYSTACK_GLOB + " files");
        }

        List<String> articles = new ArrayList<>();
        for (Path file : files) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        articles.add(requiredText(json.readTree(line), "text"));
                    }
                }
            }
        }
        return List.copyOf(articles);
    }

    private ContextBuild buildContext(
            List<String> articles,
            int scenarioIndex,
            List<String> derivations,
            int contextCharacters) {
        int needleCharacters = derivations.stream().mapToInt(String::length).sum();
        int haystackCharacters = Math.max(0, contextCharacters - needleCharacters);
        StringBuilder context = new StringBuilder(contextCharacters + derivations.size() * 2);
        int articleIndex = scenarioIndex % articles.size();
        while (context.length() < haystackCharacters) {
            String article = articles.get(articleIndex % articles.size());
            int remaining = haystackCharacters - context.length();
            context.append(article, 0, Math.min(remaining, article.length()));
            if (context.length() < haystackCharacters) {
                context.append('\n');
            }
            articleIndex++;
        }

        int baseLength = context.length();
        int[] basePositions = new int[derivations.size()];
        int[] insertionLengths = new int[derivations.size()];
        // 从后向前插入，避免较早的插入改变后续位置；多条事实均匀分布在长文本中。
        for (int index = derivations.size() - 1; index >= 0; index--) {
            int position = baseLength * (index + 1) / (derivations.size() + 1);
            String insertion = "\n" + derivations.get(index) + "\n";
            basePositions[index] = position;
            insertionLengths[index] = insertion.length();
            context.insert(position, insertion);
        }
        List<CompactEvalCorpus.Evidence> evidence = new ArrayList<>();
        int precedingInsertionCharacters = 0;
        for (int index = 0; index < derivations.size(); index++) {
            int finalPosition = basePositions[index] + precedingInsertionCharacters + 1;
            double relativePosition =
                    context.length() == 1 ? 0.0 : (double) finalPosition / (context.length() - 1);
            evidence.add(
                    new CompactEvalCorpus.Evidence(
                            "derivation-" + (index + 1),
                            null,
                            null,
                            List.of(),
                            null,
                            derivations.get(index),
                            finalPosition / 7000,
                            relativePosition,
                            CompactEvalMetadataSupport.positionPeriod(relativePosition),
                            CompactEvalMetadataSupport.positionSegment(relativePosition),
                            null,
                            finalPosition,
                            "character",
                            "generated-insertion"));
            precedingInsertionCharacters += insertionLengths[index];
        }
        return new ContextBuild(context.toString(), List.copyOf(evidence));
    }

    private static List<String> stringArray(JsonNode parent, String field) {
        JsonNode values = parent.path(field);
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedleBench field must be a non-empty array: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "NeedleBench field contains a blank value: " + field);
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("NeedleBench field is required: " + field);
        }
        return value.asText();
    }

    private record ContextBuild(String context, List<CompactEvalCorpus.Evidence> evidence) {}
}
