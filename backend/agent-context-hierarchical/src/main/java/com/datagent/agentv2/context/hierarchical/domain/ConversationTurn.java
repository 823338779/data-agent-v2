package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.List;
import java.util.Objects;

/** 按真实 user 消息划分的有序原子轮次；assistant 正文是否存在不改变轮次身份。 */
public record ConversationTurn(
        /** 轮次在完整会话历史中的连续序号，从一开始递增。 */
        long ordinal,
        /** 该轮首条持久化 user 消息的会话消息序号。 */
        long startMessageSequence,
        /** 下一条 user 出现前，该轮最后一条已读取持久化消息的会话消息序号。 */
        long endMessageSequence,
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
        if (messages.isEmpty() || messages.getFirst().role() != ContextMessage.Role.USER) {
            throw new IllegalArgumentException("Conversation turn must start with a user message");
        }
    }
}
