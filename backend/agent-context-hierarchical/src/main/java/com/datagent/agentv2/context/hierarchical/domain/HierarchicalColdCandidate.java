package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** READY job 私有持有的 durable Cold 摘要候选及其可信外部元数据。 */
public record HierarchicalColdCandidate(
        /** 由来源类别、范围和生成身份确定的候选标识。 */
        String candidateId,
        /** Cold 覆盖的首个完整问答序号。 */
        long startTurn,
        /** Cold 覆盖的最后一个完整问答序号。 */
        long endTurn,
        /** Cold 覆盖的首条持久化消息序号。 */
        long startMessageSequence,
        /** Cold 覆盖的末条持久化消息序号。 */
        long endMessageSequence,
        /** 已通过摘要 schema 和 coverage 校验的 JSON 正文。 */
        String summaryDocument,
        /** provider 与本地估算中的较大 token 数。 */
        int estimatedTokenCount,
        /** 摘要正文 schema 版本。 */
        int summarySchemaVersion,
        /** 摘要 prompt 版本。 */
        int summaryPromptVersion,
        /** 生成候选的模型 provider。 */
        String modelProvider,
        /** 生成候选的模型名称。 */
        String modelName) {

    /** 拒绝缺少 durable recovery 所需身份或倒退范围的候选。 */
    public HierarchicalColdCandidate {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(summaryDocument, "summaryDocument");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        if (candidateId.isBlank()
                || startTurn <= 0L
                || endTurn < startTurn
                || startMessageSequence <= 0L
                || endMessageSequence < startMessageSequence
                || summaryDocument.isBlank()
                || estimatedTokenCount < 0
                || summarySchemaVersion <= 0
                || summaryPromptVersion <= 0
                || modelProvider.isBlank()
                || modelName.isBlank()) {
            throw new IllegalArgumentException("Invalid hierarchical Cold candidate");
        }
    }
}
