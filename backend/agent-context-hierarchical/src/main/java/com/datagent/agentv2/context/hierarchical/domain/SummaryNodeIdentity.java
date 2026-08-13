package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** 一次 execution 可复用的不可变摘要节点生成身份。 */
public record SummaryNodeIdentity(
        /** 节点所属会话，禁止跨会话复用。 */
        String conversationSessionId,
        /** 冻结策略要求的摘要 JSON schema 版本。 */
        int summarySchemaVersion,
        /** 冻结策略要求的摘要 prompt 版本。 */
        int summaryPromptVersion,
        /** 生成摘要所用模型供应商。 */
        String modelProvider,
        /** 生成摘要所用模型名称。 */
        String modelName) {

    /** 拒绝缺少会话、版本或模型维度的不完整身份。 */
    public SummaryNodeIdentity {
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        if (summarySchemaVersion <= 0 || summaryPromptVersion <= 0) {
            throw new IllegalArgumentException("Invalid summary node identity");
        }
    }

    /** 从 checkpoint 的会话/模型和冻结策略的版本构造本次唯一期望身份。 */
    public static SummaryNodeIdentity from(
            HierarchicalCheckpoint checkpoint, HierarchicalContextPolicySnapshot policy) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(policy, "policy");
        if (checkpoint.summarySchemaVersion() != policy.summarySchemaVersion()
                || checkpoint.summaryPromptVersion() != policy.summaryPromptVersion()) {
            throw new IllegalArgumentException("Checkpoint and frozen policy versions differ");
        }
        return new SummaryNodeIdentity(
                checkpoint.conversationSessionId(),
                policy.summarySchemaVersion(),
                policy.summaryPromptVersion(),
                checkpoint.modelProvider(),
                checkpoint.modelName());
    }

    /** 校验候选节点在所有生成维度上与本次 execution 完全一致。 */
    public void validate(HierarchicalSummaryNode node) {
        Objects.requireNonNull(node, "node");
        if (!conversationSessionId.equals(node.conversationSessionId())
                || summarySchemaVersion != node.summarySchemaVersion()
                || summaryPromptVersion != node.summaryPromptVersion()
                || !modelProvider.equals(node.modelProvider())
                || !modelName.equals(node.modelName())) {
            throw new IllegalArgumentException(
                    "Summary node identity does not match planning input");
        }
    }
}
