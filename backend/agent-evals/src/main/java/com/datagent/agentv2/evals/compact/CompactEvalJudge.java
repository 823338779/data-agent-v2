package com.datagent.agentv2.evals.compact;

/** 对单个探针回答执行语义评分的独立 LLM 裁判。 */
public interface CompactEvalJudge {

    String provider();

    String model();

    default String effort() {
        return "unspecified";
    }

    default String promptVersion() {
        return "unspecified";
    }

    default String rubricVersion() {
        return "unspecified";
    }

    Judgment judge(String question, String expectedAnswer, String assistantAnswer) throws Exception;

    default Judgment judge(JudgeRequest request) throws Exception {
        return judge(request.question(), request.expectedAnswer(), request.assistantAnswer());
    }

    /** score 是辅助语义分；correct 是按题型 rubric 得出的二分类结论。 */
    record Judgment(double score, boolean correct, String reason) {}

    record JudgeRequest(
            String question,
            String expectedAnswer,
            String assistantAnswer,
            CompactEvalCorpus.ScenarioMetadata metadata) {}
}
