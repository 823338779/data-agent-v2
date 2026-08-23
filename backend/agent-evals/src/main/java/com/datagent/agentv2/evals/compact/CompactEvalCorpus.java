package com.datagent.agentv2.evals.compact;

import java.util.List;
import java.util.Map;

/** 可按顺序回放、并在末尾执行记忆探针的长对话语料。 */
public record CompactEvalCorpus(String source, List<Scenario> scenarios) {

    /** 一条独立长会话；metadata 只进入结果文档，绝不拼接到 Agent 输入。 */
    public record Scenario(
            String id,
            String title,
            ScenarioMetadata metadata,
            List<String> history,
            List<Probe> probes) {

        public Scenario(String id, String title, List<String> history, List<Probe> probes) {
            this(id, title, ScenarioMetadata.empty(), history, probes);
        }
    }

    /** 跨数据集统一的时间、位置、题型和上下文标签。 */
    public record ScenarioMetadata(
            String dataset,
            String subset,
            String questionType,
            String reasoningType,
            String difficulty,
            boolean abstention,
            String questionDate,
            String historyStartDate,
            String historyEndDate,
            int historySessionCount,
            Map<String, Integer> contextLengths,
            List<Evidence> evidence) {

        public ScenarioMetadata {
            contextLengths = contextLengths == null ? Map.of() : Map.copyOf(contextLengths);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public static ScenarioMetadata empty() {
            return new ScenarioMetadata(
                    null, null, null, null, null, false, null, null, null, 0, Map.of(), List.of());
        }
    }

    /** 一条不会泄漏给 Agent 的标准答案证据定位。 */
    public record Evidence(
            String evidenceId,
            String sessionId,
            Integer sessionIndex,
            List<Integer> messageIndexes,
            String date,
            String text,
            Integer historyTurnIndex,
            Double relativePosition,
            String positionPeriod,
            Integer positionSegment,
            Long memoryAgeDays,
            Integer sourcePositionIndex,
            String sourcePositionUnit,
            String positionSource) {

        public Evidence {
            messageIndexes = messageIndexes == null ? List.of() : List.copyOf(messageIndexes);
        }
    }

    /** 需要 Agent 基于完整历史回答的问题及其参考答案。 */
    public record Probe(String question, String expectedAnswer) {}
}
