package com.datagent.agentv2.evals.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 加载本地标准语料或 LongMemEval 官方 JSON，不依赖产品运行时模块。 */
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
        JsonNode root = json.readTree(path.toFile());
        if (root.isObject() && SCHEMA_VERSION.equals(text(root, SCHEMA_VERSION_FIELD))) {
            return loadNative(root);
        }
        if (root.isArray()) {
            return loadLongMemEval(root);
        }
        throw new IllegalArgumentException("Unsupported compact evaluation corpus: " + path);
    }

    /** 标准格式保持用户给出的历史顺序，并拒绝不完整的场景。 */
    private CompactEvalCorpus loadNative(JsonNode root) {
        String source = requiredText(root, SOURCE_FIELD);
        List<CompactEvalCorpus.Scenario> scenarios = new ArrayList<>();
        for (JsonNode scenario : requiredArray(root, SCENARIOS_FIELD)) {
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
                            List.copyOf(history),
                            List.copyOf(probes)));
        }
        return new CompactEvalCorpus(source, List.copyOf(scenarios));
    }

    /** LongMemEval 的每个 haystack session 被封装为一轮历史输入，最后追加官方问题。 */
    private CompactEvalCorpus loadLongMemEval(JsonNode root) {
        List<CompactEvalCorpus.Scenario> scenarios = new ArrayList<>();
        for (JsonNode item : root) {
            List<String> dates = stringArray(item, HAYSTACK_DATES_FIELD);
            JsonNode sessions = requiredArray(item, HAYSTACK_SESSIONS_FIELD);
            if (dates.size() != sessions.size()) {
                throw new IllegalArgumentException(
                        "LongMemEval dates and sessions have different sizes");
            }

            // 每个原始 session 整体注入，避免把历史 assistant 文本伪装成当前服务生成的消息。
            List<String> history = new ArrayList<>();
            for (int index = 0; index < sessions.size(); index++) {
                history.addAll(
                        splitArchivedSession(
                                renderArchivedSession(dates.get(index), sessions.get(index))));
            }
            scenarios.add(
                    new CompactEvalCorpus.Scenario(
                            requiredText(item, QUESTION_ID_FIELD),
                            LONG_MEM_EVAL_SOURCE + " - " + requiredText(item, QUESTION_ID_FIELD),
                            List.copyOf(history),
                            List.of(
                                    new CompactEvalCorpus.Probe(
                                            requiredText(item, QUESTION_FIELD),
                                            requiredText(item, ANSWER_FIELD)))));
        }
        return new CompactEvalCorpus(LONG_MEM_EVAL_SOURCE, List.copyOf(scenarios));
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

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
