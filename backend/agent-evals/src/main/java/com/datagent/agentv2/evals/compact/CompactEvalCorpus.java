package com.datagent.agentv2.evals.compact;

import java.util.List;

/** 可按顺序回放、并在末尾执行记忆探针的长对话语料。 */
public record CompactEvalCorpus(String source, List<Scenario> scenarios) {

    /** 一条独立长会话，历史片段只用于积累上下文，探针用于读取最终回答。 */
    public record Scenario(String id, String title, List<String> history, List<Probe> probes) {}

    /** 需要 Agent 基于完整历史回答的问题及其参考答案。 */
    public record Probe(String question, String expectedAnswer) {}
}
