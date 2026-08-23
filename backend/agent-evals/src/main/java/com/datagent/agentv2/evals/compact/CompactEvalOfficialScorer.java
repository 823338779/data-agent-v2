package com.datagent.agentv2.evals.compact;

import com.huaban.analysis.jieba.JiebaSegmenter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按各数据集公开协议计算主分；不支持官方协议的自定义语料明确标为不适用。 */
final class CompactEvalOfficialScorer {

    private static final String COMPLETED = "completed";
    private static final String NOT_APPLICABLE = "not-applicable";
    private static final String LONG_MEM_EVAL = "LongMemEval";
    private static final String C_LONG_EVAL = "CLongEval";
    private static final String NEEDLE_BENCH = "NeedleBench";
    private static final String LONG_MEM_METHOD = "longmemeval-llm-accuracy-compatible/v1";
    private static final String C_LONG_METHOD = "clongeval-lcvmem-qa-f1-zh-compatible/v1";
    private static final String NEEDLE_METHOD = "needlebench-multi-keyword-recall/v1";

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    boolean supports(CompactEvalCorpus.ScenarioMetadata metadata) {
        return metadata != null
                && (LONG_MEM_EVAL.equals(metadata.dataset())
                        || C_LONG_EVAL.equals(metadata.dataset())
                        || NEEDLE_BENCH.equals(metadata.dataset()));
    }

    CompactEvalReport.OfficialScore score(
            CompactEvalCorpus.ScenarioMetadata metadata,
            String expectedAnswer,
            String assistantAnswer,
            CompactEvalJudge.Judgment judgment) {
        if (metadata == null) {
            return notApplicable();
        }
        return switch (metadata.dataset()) {
            case LONG_MEM_EVAL ->
                    completed(
                            LONG_MEM_METHOD,
                            judgment.correct() ? 1.0 : 0.0,
                            judgment.correct(),
                            judgment.reason());
            case C_LONG_EVAL ->
                    completed(
                            C_LONG_METHOD,
                            chineseQaF1(assistantAnswer, expectedAnswer),
                            null,
                            "中文分词规范化后的 QA F1");
            case NEEDLE_BENCH ->
                    completed(
                            NEEDLE_METHOD,
                            needleKeywordRecall(assistantAnswer, expectedAnswer),
                            null,
                            "参考答案关键词命中率");
            default -> notApplicable();
        };
    }

    CompactEvalReport.OfficialScore failed(
            CompactEvalCorpus.ScenarioMetadata metadata, String failure) {
        if (!supports(metadata)) {
            return notApplicable();
        }
        // CLongEval 和 NeedleBench 是本地确定性评分，不应随 LLM Judge 失败而失败。
        if (!LONG_MEM_EVAL.equals(metadata.dataset())) {
            throw new IllegalArgumentException(
                    "Only LongMemEval official scoring depends on Judge");
        }
        return new CompactEvalReport.OfficialScore(
                LONG_MEM_METHOD, "failed", null, null, null, failure);
    }

    CompactEvalReport.OfficialScore skipped(
            CompactEvalCorpus.ScenarioMetadata metadata, String reason) {
        if (!supports(metadata)) {
            return notApplicable();
        }
        return new CompactEvalReport.OfficialScore(
                method(metadata), "skipped", null, null, null, reason);
    }

    CompactEvalReport.OfficialScore scoreWithoutJudge(
            CompactEvalCorpus.ScenarioMetadata metadata,
            String expectedAnswer,
            String assistantAnswer) {
        if (metadata == null || LONG_MEM_EVAL.equals(metadata.dataset())) {
            return notApplicable();
        }
        return score(
                metadata,
                expectedAnswer,
                assistantAnswer,
                new CompactEvalJudge.Judgment(0.0, false, "未使用 LLM Judge"));
    }

    private double chineseQaF1(String prediction, String groundTruth) {
        List<String> predictionTokens = tokenize(firstAnswerLine(prediction));
        List<String> groundTruthTokens = tokenize(groundTruth);
        if (predictionTokens.isEmpty() || groundTruthTokens.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> remaining = counts(groundTruthTokens);
        int common = 0;
        for (String token : predictionTokens) {
            int count = remaining.getOrDefault(token, 0);
            if (count > 0) {
                common++;
                remaining.put(token, count - 1);
            }
        }
        if (common == 0) {
            return 0.0;
        }
        double precision = (double) common / predictionTokens.size();
        double recall = (double) common / groundTruthTokens.size();
        return 2 * precision * recall / (precision + recall);
    }

    private List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        for (String token : segmenter.sentenceProcess(value == null ? "" : value)) {
            String normalized = normalizeToken(token);
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private static String firstAnswerLine(String prediction) {
        if (prediction == null) {
            return "";
        }
        String firstLine = prediction.lines().findFirst().orElse("").strip();
        int repeatedQuestion = firstLine.indexOf("问题：");
        return repeatedQuestion < 0 ? firstLine : firstLine.substring(0, repeatedQuestion);
    }

    private static String normalizeToken(String token) {
        StringBuilder normalized = new StringBuilder();
        token.toLowerCase(Locale.ROOT)
                .codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static Map<String, Integer> counts(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        tokens.forEach(token -> counts.merge(token, 1, Integer::sum));
        return counts;
    }

    private static double needleKeywordRecall(String prediction, String groundTruth) {
        String candidate = prediction == null ? "" : prediction.toLowerCase(Locale.ROOT);
        String[] keywords = groundTruth.toLowerCase(Locale.ROOT).strip().split("\\s+");
        int matched = 0;
        for (String keyword : keywords) {
            if (!keyword.isEmpty() && candidate.contains(keyword)) {
                matched++;
            }
        }
        return keywords.length == 0 ? 0.0 : (double) matched / keywords.length;
    }

    private static CompactEvalReport.OfficialScore completed(
            String method, double score, Boolean passed, String reason) {
        return new CompactEvalReport.OfficialScore(method, COMPLETED, score, passed, reason, null);
    }

    private static String method(CompactEvalCorpus.ScenarioMetadata metadata) {
        return switch (metadata.dataset()) {
            case LONG_MEM_EVAL -> LONG_MEM_METHOD;
            case C_LONG_EVAL -> C_LONG_METHOD;
            case NEEDLE_BENCH -> NEEDLE_METHOD;
            default -> "none";
        };
    }

    private static CompactEvalReport.OfficialScore notApplicable() {
        return new CompactEvalReport.OfficialScore(
                "none", NOT_APPLICABLE, null, null, "数据集未定义官方评分协议", null);
    }
}
