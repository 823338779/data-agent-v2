package com.datagent.agentv2.context.lsm.planning;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextMessage.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将模型协议消息还原为不可拆分的完整或当前问答。 */
public final class ConversationTurnGrouper {

    /** 仅用户消息开启新问答，因此 retry 未重新写入用户消息时会归入同一问答；工具调用和 observation 始终跟随其所属用户消息，避免破坏模型工具协议原子性。 */
    public List<ConversationTurn> group(List<ContextMessage> history) {
        Objects.requireNonNull(history, "history");
        List<ConversationTurn> turns = new ArrayList<>();
        List<ContextMessage> currentMessages = null;
        long turnSequence = 0L;

        // 用户消息是唯一的问答边界；先提交上一轮，再开始新的当前轮。
        for (ContextMessage message : history) {
            Objects.requireNonNull(message, "history message");
            if (message.role() == Role.USER) {
                if (currentMessages != null) {
                    turns.add(toTurn(turnSequence, currentMessages));
                }
                turnSequence++;
                currentMessages = new ArrayList<>();
            }
            if (currentMessages != null) {
                currentMessages.add(message);
            }
        }

        // 最后一轮可能尚未产生最终回答，也必须作为当前未完成轮保留。
        if (currentMessages != null) {
            turns.add(toTurn(turnSequence, currentMessages));
        }
        return List.copyOf(turns);
    }

    private static ConversationTurn toTurn(long turnSequence, List<ContextMessage> messages) {
        boolean completed = isCompleted(messages);
        return new ConversationTurn(turnSequence, messages, completed);
    }

    private static boolean isCompleted(List<ContextMessage> messages) {
        // 只接受当前问答末尾的最终回答；旧 attempt 的 partial 输出不能完成后续 retry。
        ContextMessage lastMessage = messages.getLast();
        return lastMessage.role() == Role.ASSISTANT
                && !lastMessage.content().isBlank()
                && lastMessage.toolCalls().isEmpty();
    }
}
