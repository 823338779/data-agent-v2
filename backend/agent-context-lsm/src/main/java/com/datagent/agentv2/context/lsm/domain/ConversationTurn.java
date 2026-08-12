package com.datagent.agentv2.context.lsm.domain;

import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.List;
import java.util.Objects;

/** 按触发用户消息聚合的有序问答单元，供 LSM 仅压缩完整历史时使用。 */
public record ConversationTurn(
        long turnSequence, List<ContextMessage> messages, boolean completed) {

    public ConversationTurn {
        if (turnSequence <= 0L) {
            throw new IllegalArgumentException("turnSequence must be positive");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("turn messages must not be empty");
        }
    }

    /** 返回 LSM 区间计算使用的连续问答序号。 */
    public long sequence() {
        return turnSequence;
    }
}
