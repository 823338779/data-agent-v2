package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.List;
import java.util.Objects;

/** Hierarchical assembler 基于当前完整会话历史构造的一次规划状态。 */
public record HierarchicalContextState(
        /** 本次上下文准备请求及固定框架输入。 */
        ConversationContextRequest request,
        /** execution 首次使用时冻结的 hierarchical 策略。 */
        HierarchicalContextPolicySnapshot policy,
        /** 仅按 user 边界划分的完整有序轮次，不区分历史、当前或执行终态。 */
        List<ConversationTurn> turns,
        /** 本次实际加载到的最后持久化消息序号；空会话为零。 */
        long loadedThroughMessageSequence,
        /** 当前正式发布的摘要检查点。 */
        HierarchicalCheckpoint checkpoint,
        /** 当前完整模型输入的保守 token 估算。 */
        int estimatedTokens) {






    /** 冻结可变集合并校验状态中的必需对象和非负估算。 */
    public HierarchicalContextState {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (loadedThroughMessageSequence < 0L || estimatedTokens < 0) {
            throw new IllegalArgumentException("Invalid hierarchical context state");
        }
    }
}
