package com.datagent.agentv2.evals.compact;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;

/** 加载本地标准语料及受支持的官方评测数据，不依赖产品运行时模块。 */
@RequiredArgsConstructor
public final class CompactEvalCorpusLoader {

    private static final String SCHEMA_VERSION = "compact-eval/v1";
    private static final String LONG_MEM_EVAL_SOURCE = "LongMemEval";
    private static final String ARCHIVE_HEADER = "以下是需要保留的历史会话记录，日期：";
    private static final String ARCHIVE_FOOTER = "\n请仅确认已经读取该历史记录，不要调用工具，也不要补充记录中不存在的事实。";
    private static final String ROLE_SEPARATOR = ": ";
    private static final String LINE_SEPARATOR = "\n";
    private static final String CHUNK_HEADER_PREFIX = "历史会话记录分片 ";
    private static final String CHUNK_HEADER_MIDDLE = "/";
    private static final String CHUNK_HEADER_SUFFIX = "（请按顺序保留）：\n";
    private static final int ARCHIVE_CHUNK_CONTENT_LIMIT = 7000;
    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
    private static final String SOURCE_FIELD = "source";
    private static final String SCENARIOS_FIELD = "scenarios";
    private static final String HISTORY_FIELD = "history";
    private static final String PROBES_FIELD = "probes";
    private static final String ID_FIELD = "id";
    private static final String TITLE_FIELD = "title";
    private static final String QUESTION_FIELD = "question";
    private static final String EXPECTED_ANSWER_FIELD = "expectedAnswer";
    private static final String QUESTION_ID_FIELD = "question_id";
    private static final String ANSWER_FIELD = "answer";
    private static final String HAYSTACK_DATES_FIELD = "haystack_dates";
    private static final String HAYSTACK_SESSIONS_FIELD = "haystack_sessions";
    private static final String ROLE_FIELD = "role";
    private static final String CONTENT_FIELD = "content";

    private final ObjectMapper json;

    /** 根据根节点形态自动识别标准语料或 LongMemEval 数据文件。 */
    public CompactEvalCorpus load(Path path) throws IOException {
        return load(path, 0, NeedleBenchZhCorpusLoader.DEFAULT_CONTEXT_CHARACTERS);
    }

    /** 在读取阶段限制场景数，避免试跑少量 case 时把大型语料整体装入内存。 */
    public CompactEvalCorpus load(Path path, int maxScenarios) throws IOException {
        return load(path, maxScenarios, NeedleBenchZhCorpusLoader.DEFAULT_CONTEXT_CHARACTERS);
    }

    /** 自动识别语料格式；NeedleBench 可指定生成场景的目标字符数。 */
    public CompactEvalCorpus load(Path path, int maxScenarios, int needleContextCharacters)
            throws IOException {
        if (Files.isDirectory(path)
                && Files.isRegularFile(path.resolve(NeedleBenchZhCorpusLoader.NEEDLE_FILE))) {
            return new NeedleBenchZhCorpusLoader(json)
                    .load(path, maxScenarios, needleContextCharacters);
        }
        if (Files.isRegularFile(path)
                && path.getFileName()
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".jsonl")) {
            return new CLongEvalCorpusLoader(json).load(path, maxScenarios);
        }
        try (InputStream input = open(path);
                JsonParser parser = json.getFactory().createParser(input)) {
            JsonToken rootToken = parser.nextToken();
            if (JsonToken.START_OBJECT.equals(rootToken)) {
                JsonNode root = json.readTree(parser);
                if (SCHEMA_VERSION.equals(text(root, SCHEMA_VERSION_FIELD))) {
                    return loadNative(root, maxScenarios);
                }
            }
            if (JsonToken.START_ARRAY.equals(rootToken)) {
                return loadLongMemEval(parser, maxScenarios, path);
            }
        }
        throw new IllegalArgumentException("Unsupported compact evaluation corpus: " + path);
    }

    /** resources 中的大型官方语料使用 Git LFS 管理的 gzip 文件，评测时直接流式解压。 */
    private static InputStream open(Path path) throws IOException {
        InputStream input = Files.newInputStream(path);
        if (!path.getFileName().toString().endsWith(".gz")) {
            return input;
        }
        try {
            return new GZIPInputStream(input);
        } catch (IOException | RuntimeException failure) {
            // gzip 头解析发生在构造期，此时外层 try-with-resources 尚未接管原始流。
            try {
                input.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** 标准格式保持用户给出的历史顺序，并拒绝不完整的场景。 */
    private CompactEvalCorpus loadNative(JsonNode root, int maxScenarios) {
        String source = requiredText(root, SOURCE_FIELD);
        List<CompactEvalCorpus.Scenario> scenarios = new ArrayList<>();
        for (JsonNode scenario : requiredArray(root, SCENARIOS_FIELD)) {
            if (reachedLimit(scenarios.size(), maxScenarios)) {
                break;
            }
            List<String> history = stringArray(scenario, HISTORY_FIELD);
            List<CompactEvalCorpus.Probe> probes = new ArrayList<>();
            for (JsonNode probe : requiredArray(scenario, PROBES_FIELD)) {
                probes.add(
                        new CompactEvalCorpus.Probe(
                                requiredText(probe, QUESTION_FIELD),
                                requiredText(probe, EXPECTED_ANSWER_FIELD)));
            }
            scenarios.add(
                    new CompactEvalCorpus.Scenario(
                            requiredText(scenario, ID_FIELD),
                            requiredText(scenario, TITLE_FIELD),
                            new CompactEvalCorpus.ScenarioMetadata(
                                    source,
                                    "native",
                                    null,
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    history.size(),
                                    Map.of(
                                            "characters",
                                            history.stream().mapToInt(String::length).sum()),
                                    List.of()),
                            List.copyOf(history),
                            List.copyOf(probes)));
        }
        return new CompactEvalCorpus(source, List.copyOf(scenarios));
    }

    /** LongMemEval 的每个 haystack session 被封装为一轮历史输入，最后追加官方问题。 */
    private CompactEvalCorpus loadLongMemEval(JsonParser parser, int maxScenarios, Path path)
            throws IOException {
        List<CompactEvalCorpus.Scenario> scenarios = new ArrayList<>();
        while (!reachedLimit(scenarios.size(), maxScenarios)
                && !JsonToken.END_ARRAY.equals(parser.nextToken())) {
            JsonNode item = json.readTree(parser);
            List<String> dates = stringArray(item, HAYSTACK_DATES_FIELD);
            JsonNode sessions = requiredArray(item, HAYSTACK_SESSIONS_FIELD);
            if (dates.size() != sessions.size()) {
                throw new IllegalArgumentException(
                        "LongMemEval dates and sessions have different sizes");
            }

            // 每个原始 session 整体注入，避免把历史 assistant 文本伪装成当前服务生成的消息。
            List<String> history = new ArrayList<>();
            List<Integer> historyTurnStarts = new ArrayList<>();
            for (int index = 0; index < sessions.size(); index++) {
                historyTurnStarts.add(history.size());
                history.addAll(
                        splitArchivedSession(
                                renderArchivedSession(dates.get(index), sessions.get(index))));
            }
            String questionId = requiredText(item, QUESTION_ID_FIELD);
            scenarios.add(
                    new CompactEvalCorpus.Scenario(
                            questionId,
                            LONG_MEM_EVAL_SOURCE + " - " + questionId,
                            longMemEvalMetadata(
                                    item, path, dates, sessions, historyTurnStarts, history),
                            List.copyOf(history),
                            List.of(
                                    new CompactEvalCorpus.Probe(
                                            requiredText(item, QUESTION_FIELD),
                                            requiredScalarText(item, ANSWER_FIELD)))));
        }
        return new CompactEvalCorpus(LONG_MEM_EVAL_SOURCE, List.copyOf(scenarios));
    }

    private CompactEvalCorpus.ScenarioMetadata longMemEvalMetadata(
            JsonNode item,
            Path path,
            List<String> dates,
            JsonNode sessions,
            List<Integer> historyTurnStarts,
            List<String> history) {
        List<String> sessionIds = optionalStringArray(item, "haystack_session_ids");
        Set<String> answerSessionIds =
                new HashSet<>(optionalStringArray(item, "answer_session_ids"));
        String questionDate = CompactEvalMetadataSupport.normalizeDate(text(item, "question_date"));
        List<CompactEvalCorpus.Evidence> evidence = new ArrayList<>();

        for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
            JsonNode session = sessions.get(sessionIndex);
            String sessionId =
                    sessionIndex < sessionIds.size()
                            ? sessionIds.get(sessionIndex)
                            : "session-" + sessionIndex;
            List<Integer> messageIndexes = new ArrayList<>();
            List<String> evidenceTexts = new ArrayList<>();
            for (int messageIndex = 0; messageIndex < session.size(); messageIndex++) {
                JsonNode message = session.get(messageIndex);
                if (message.path("has_answer").asBoolean(false)) {
                    messageIndexes.add(messageIndex);
                    String content = text(message, CONTENT_FIELD);
                    if (content != null) {
                        evidenceTexts.add(content);
                    }
                }
            }
            if (!answerSessionIds.contains(sessionId) && messageIndexes.isEmpty()) {
                continue;
            }
            double relativePosition =
                    sessions.size() == 1 ? 0.0 : (double) sessionIndex / (sessions.size() - 1);
            String evidenceDate = CompactEvalMetadataSupport.normalizeDate(dates.get(sessionIndex));
            evidence.add(
                    new CompactEvalCorpus.Evidence(
                            sessionId,
                            sessionId,
                            sessionIndex,
                            List.copyOf(messageIndexes),
                            evidenceDate,
                            evidenceTexts.isEmpty() ? null : String.join("\n", evidenceTexts),
                            historyTurnStarts.get(sessionIndex),
                            relativePosition,
                            CompactEvalMetadataSupport.positionPeriod(relativePosition),
                            CompactEvalMetadataSupport.positionSegment(relativePosition),
                            CompactEvalMetadataSupport.memoryAgeDays(questionDate, evidenceDate),
                            null,
                            null,
                            answerSessionIds.contains(sessionId)
                                    ? "answer_session_ids"
                                    : "has_answer"));
        }

        Map<String, Integer> contextLengths = new LinkedHashMap<>();
        contextLengths.put("characters", history.stream().mapToInt(String::length).sum());
        return new CompactEvalCorpus.ScenarioMetadata(
                LONG_MEM_EVAL_SOURCE,
                longMemEvalSubset(path),
                text(item, "question_type"),
                null,
                null,
                requiredText(item, QUESTION_ID_FIELD).endsWith("_abs"),
                questionDate,
                CompactEvalMetadataSupport.earliest(dates),
                CompactEvalMetadataSupport.latest(dates),
                sessions.size(),
                contextLengths,
                evidence);
    }

    private static String longMemEvalSubset(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("oracle")) {
            return "oracle";
        }
        if (name.contains("_m_")) {
            return "m-cleaned";
        }
        return name.contains("_s_") ? "s-cleaned" : "unknown";
    }

    private static boolean reachedLimit(int loadedScenarios, int maxScenarios) {
        return maxScenarios > 0 && loadedScenarios >= maxScenarios;
    }

    /** 转录中只保留角色和正文，不向被测 Agent 泄漏 has_answer 等标注。 */
    private String renderArchivedSession(String date, JsonNode session) {
        if (!session.isArray()) {
            throw new IllegalArgumentException("LongMemEval haystack session must be an array");
        }
        StringBuilder rendered =
                new StringBuilder(ARCHIVE_HEADER).append(date).append(LINE_SEPARATOR);
        for (JsonNode message : session) {
            rendered.append(requiredText(message, ROLE_FIELD).toUpperCase())
                    .append(ROLE_SEPARATOR)
                    .append(requiredText(message, CONTENT_FIELD))
                    .append(LINE_SEPARATOR);
        }
        return rendered.append(ARCHIVE_FOOTER).toString();
    }

    /** 公开消息接口限制 8000 字符，预留分片说明后把超长 session 切成连续输入。 */
    private List<String> splitArchivedSession(String rendered) {
        if (rendered.length() <= ARCHIVE_CHUNK_CONTENT_LIMIT) {
            return List.of(rendered);
        }
        int chunkCount =
                (rendered.length() + ARCHIVE_CHUNK_CONTENT_LIMIT - 1) / ARCHIVE_CHUNK_CONTENT_LIMIT;
        List<String> chunks = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int start = index * ARCHIVE_CHUNK_CONTENT_LIMIT;
            int end = Math.min(start + ARCHIVE_CHUNK_CONTENT_LIMIT, rendered.length());
            chunks.add(
                    CHUNK_HEADER_PREFIX
                            + (index + 1)
                            + CHUNK_HEADER_MIDDLE
                            + chunkCount
                            + CHUNK_HEADER_SUFFIX
                            + rendered.substring(start, end));
        }
        return chunks;
    }

    /** 读取字符串数组，并统一执行非空校验。 */
    private List<String> stringArray(JsonNode parent, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : requiredArray(parent, field)) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(field + " contains a blank value");
            }
            values.add(value.asText());
        }
        return values;
    }

    private List<String> optionalStringArray(JsonNode parent, String field) {
        JsonNode values = parent.path(field);
        if (!values.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText());
            }
        }
        return List.copyOf(result);
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty array");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        String value = text(parent, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /** 官方 LongMemEval 中少量答案是 JSON 数字，统一转成文本后参与评分。 */
    private static String requiredScalarText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        String text = value.isValueNode() && !value.isNull() ? value.asText() : null;
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
