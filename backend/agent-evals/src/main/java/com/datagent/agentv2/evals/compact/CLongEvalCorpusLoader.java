package com.datagent.agentv2.evals.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 CLongEval 的 Long Conversation Memory JSONL 转成统一评测语料。 */
final class CLongEvalCorpusLoader {

    private static final String SOURCE_PREFIX = "CLongEval/LCvMem/";
    private static final String QUESTION_SUFFIX = "问题：";
    private static final String POSITION_FILE = "long_conversation_memory_with_pos.jsonl";
    private static final Pattern CONTEXT_DATE =
            Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern QUERY_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");

    private final ObjectMapper json;

    CLongEvalCorpusLoader(ObjectMapper json) {
        this.json = json;
    }

    CompactEvalCorpus load(Path path, int maxScenarios) throws IOException {
        Map<String, PositionInfo> positions = loadPositions(path.resolveSibling(POSITION_FILE));
        List<CompactEvalCorpus.Scenario> scenarios = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null
                    && !reachedLimit(scenarios.size(), maxScenarios)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode item = json.readTree(line);
                String id = requiredText(item, "id");
                String context = removeQuestionSuffix(requiredText(item, "context"));
                List<String> history = CompactEvalTextSplitter.split(context, "CLongEval 中文长对话");
                PositionInfo position = positions.get(id);
                if (position == null) {
                    position = answerTextPosition(context, requiredText(item, "answer"));
                }
                scenarios.add(
                        new CompactEvalCorpus.Scenario(
                                id,
                                "CLongEval LCvMem - " + id,
                                metadata(item, path, context, history, position),
                                history,
                                List.of(
                                        new CompactEvalCorpus.Probe(
                                                requiredText(item, "query"),
                                                requiredText(item, "answer")))));
            }
        }
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("CLongEval corpus contains no scenarios: " + path);
        }
        return new CompactEvalCorpus(
                SOURCE_PREFIX + stripExtensions(path.getFileName().toString()),
                List.copyOf(scenarios));
    }

    private CompactEvalCorpus.ScenarioMetadata metadata(
            JsonNode item, Path path, String context, List<String> history, PositionInfo position) {
        List<String> dates = contextDates(context);
        String evidenceDate = queryEvidenceDate(requiredText(item, "query"), dates);
        Map<String, Integer> contextLengths = new LinkedHashMap<>();
        contextLengths.put("characters", context.length());
        copyInteger(item, "chatglm_length", contextLengths);
        copyInteger(item, "qwen_length", contextLengths);
        copyInteger(item, "internlm2_length", contextLengths);

        List<CompactEvalCorpus.Evidence> evidence = List.of();
        if (position != null) {
            evidence =
                    List.of(
                            new CompactEvalCorpus.Evidence(
                                    requiredText(item, "id"),
                                    null,
                                    null,
                                    List.of(),
                                    evidenceDate,
                                    position.evidenceText(),
                                    historyTurnIndex(history, position.meanPosition()),
                                    position.meanPosition(),
                                    CompactEvalMetadataSupport.positionPeriod(
                                            position.meanPosition()),
                                    CompactEvalMetadataSupport.positionSegment(
                                            position.meanPosition()),
                                    null,
                                    position.positionIndex(),
                                    position.positionUnit(),
                                    position.source()));
            if ("official-position-file".equals(position.source())) {
                contextLengths.put("official_context_length", position.contextLength());
            }
        }
        return new CompactEvalCorpus.ScenarioMetadata(
                "CLongEval",
                stripExtensions(path.getFileName().toString()),
                "long-conversation-memory",
                null,
                null,
                false,
                null,
                CompactEvalMetadataSupport.earliest(dates),
                CompactEvalMetadataSupport.latest(dates),
                dates.size(),
                contextLengths,
                evidence);
    }

    private Map<String, PositionInfo> loadPositions(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        Map<String, PositionInfo> positions = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode item = json.readTree(line);
                positions.put(
                        requiredText(item, "id"),
                        new PositionInfo(
                                requiredInteger(item, "context_length"),
                                requiredInteger(item, "position_idx"),
                                requiredDouble(item, "mean_pos"),
                                "dataset-index",
                                "official-position-file",
                                null));
            }
        }
        return Map.copyOf(positions);
    }

    private static List<String> contextDates(String context) {
        List<String> dates = new ArrayList<>();
        Matcher matcher = CONTEXT_DATE.matcher(context);
        while (matcher.find()) {
            String date =
                    CompactEvalMetadataSupport.normalizeDate(
                            matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3));
            if (!dates.contains(date)) {
                dates.add(date);
            }
        }
        return List.copyOf(dates);
    }

    private static String queryEvidenceDate(String query, List<String> contextDates) {
        Matcher matcher = QUERY_DATE.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String suffix =
                "-"
                        + String.format("%02d", Integer.parseInt(matcher.group(1)))
                        + "-"
                        + String.format("%02d", Integer.parseInt(matcher.group(2)));
        return contextDates.stream().filter(date -> date.endsWith(suffix)).findFirst().orElse(null);
    }

    private static int historyTurnIndex(List<String> history, double relativePosition) {
        return Math.min(Math.max(0, history.size() - 1), (int) (relativePosition * history.size()));
    }

    private static PositionInfo answerTextPosition(String context, String answer) {
        int position = context.indexOf(answer);
        if (position < 0) {
            return null;
        }
        double relativePosition =
                context.length() == 1 ? 0.0 : (double) position / (context.length() - 1);
        return new PositionInfo(
                context.length(),
                position,
                relativePosition,
                "character",
                "answer-text-fallback",
                answer);
    }

    private static void copyInteger(
            JsonNode item, String field, Map<String, Integer> contextLengths) {
        if (item.path(field).canConvertToInt()) {
            contextLengths.put(field, item.path(field).intValue());
        }
    }

    private static String removeQuestionSuffix(String context) {
        String stripped = context.stripTrailing();
        return stripped.endsWith(QUESTION_SUFFIX)
                ? stripped.substring(0, stripped.length() - QUESTION_SUFFIX.length())
                        .stripTrailing()
                : stripped;
    }

    private static String stripExtensions(String name) {
        int extension = name.indexOf('.');
        return extension < 0 ? name : name.substring(0, extension);
    }

    private static boolean reachedLimit(int loadedScenarios, int maxScenarios) {
        return maxScenarios > 0 && loadedScenarios >= maxScenarios;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("CLongEval field is required: " + field);
        }
        return value.asText();
    }

    private static int requiredInteger(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("CLongEval integer field is required: " + field);
        }
        return value.intValue();
    }

    private static double requiredDouble(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("CLongEval number field is required: " + field);
        }
        return value.doubleValue();
    }

    private record PositionInfo(
            int contextLength,
            int positionIndex,
            double meanPosition,
            String positionUnit,
            String source,
            String evidenceText) {}
}
