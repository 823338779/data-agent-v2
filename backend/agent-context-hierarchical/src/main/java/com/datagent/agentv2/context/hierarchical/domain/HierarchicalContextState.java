package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.List;
import java.util.Objects;

/** Hierarchical assembler 在一次冻结输入边界内使用的完整规划状态。 */
public record HierarchicalContextState(
        /** 本次上下文准备请求及固定框架输入。 */
        ConversationContextRequest request,
        /** execution 首次使用时冻结的 hierarchical 策略。 */
        HierarchicalContextPolicySnapshot policy,
        /** 可参与分层摘要的连续完整问答。 */
        List<ConversationTurn> completedTurns,
        /** 不可压缩的当前未完成问答；不存在时为空。 */
        ConversationTurn currentTurn,
        /** 当前正式发布的摘要检查点。 */
        HierarchicalCheckpoint checkpoint,
        /** 当前完整模型输入的保守 token 估算。 */
        int estimatedTokens) {

    /** 冻结可变集合并校验状态中的必需对象和非负估算。 */
    public HierarchicalContextState {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        completedTurns = List.copyOf(Objects.requireNonNull(completedTurns, "completedTurns"));
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
    }
}
