package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.List;
import java.util.Objects;

/** 按触发用户消息划分的有序原子问答，工具调用及其 observation 不会跨越该边界。 */
public record ConversationTurn(
        /** 问答在当前有界历史中的连续序号，从一开始递增。 */
        long ordinal,
        /** 该问答首条持久化用户消息的会话消息序号。 */
        long startMessageSequence,
        /** 该问答最后一条已读取持久化消息的会话消息序号。 */
        long endMessageSequence,
        /** 最后一条持久化 assistant 行是否已经形成非空最终回答。 */
        boolean completed,
        /** 按模型协议顺序重建且不可拆分的消息。 */
        List<ContextMessage> messages) {

    /** 固定问答身份与不可变消息副本，拒绝倒退或空范围。 */
    public ConversationTurn {
        if (ordinal <= 0L
                || startMessageSequence <= 0L
                || endMessageSequence < startMessageSequence) {
            throw new IllegalArgumentException("Invalid conversation turn boundary");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Conversation turn messages must not be empty");
        }
    }
}
