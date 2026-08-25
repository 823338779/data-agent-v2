package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextMessage.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将有序模型协议消息聚合为由真实用户消息触发的不可拆分问答。 */
public final class ConversationTurnGrouper {

    /** 为纯协议消息提供便捷分组；列表位置被用作稳定的本地消息序号。 */
    public List<ConversationTurn> group(List<ContextMessage> history) {
        Objects.requireNonNull(history, "history");
        List<SourceMessage> sources = new ArrayList<>(history.size());

        // 每条普通协议消息形成一个来源单元；轮次边界只由 user 消息决定。
        for (int index = 0; index < history.size(); index++) {
            ContextMessage message = Objects.requireNonNull(history.get(index), "history message");
            long sequence = index + 1L;
            if (message.role() == Role.USER) {
                sources.add(SourceMessage.user(sequence, message));
            } else if (message.role() == Role.ASSISTANT) {
                sources.add(SourceMessage.assistant(sequence, List.of(message)));
            } else {
                sources.add(SourceMessage.protocol(sequence, message));
            }
        }
        return groupSources(sources);
    }

    /** 使用持久化源消息边界分组；空 assistant 仍推进所属轮次的结束序号。 */
    public List<ConversationTurn> groupSources(List<SourceMessage> sources) {
        Objects.requireNonNull(sources, "sources");
        List<ConversationTurn> turns = new ArrayList<>();
        List<ContextMessage> currentMessages = null;
        long ordinal = 0L;
        long startSequence = 0L;
        long endSequence = 0L;
        long previousSequence = 0L;

        // 只有真实 user 来源开启新轮次，assistant 与协议来源都不能改变轮次身份。
        for (SourceMessage source : sources) {
            Objects.requireNonNull(source, "source message");
            if (source.messageSequence() <= previousSequence) {
                throw new IllegalArgumentException("Source message sequences must increase");
            }
            previousSequence = source.messageSequence();
            if (source.kind() == SourceKind.USER) {
                if (currentMessages != null) {
                    turns.add(toTurn(ordinal, startSequence, endSequence, currentMessages));
                }
                ordinal++;
                startSequence = source.messageSequence();
                currentMessages = new ArrayList<>();
            }
            if (currentMessages == null) {
                continue;
            }
            endSequence = source.messageSequence();
            currentMessages.addAll(source.messages());
        }

        // 最后一轮即使只有 user 消息也必须保留；是否已有回答不影响历史语义。
        if (currentMessages != null) {
            turns.add(toTurn(ordinal, startSequence, endSequence, currentMessages));
        }
        return List.copyOf(turns);
    }

    private static ConversationTurn toTurn(
            long ordinal, long startSequence, long endSequence, List<ContextMessage> messages) {
        return new ConversationTurn(ordinal, startSequence, endSequence, List.copyOf(messages));
    }

    /** 一条持久化消息展开后的协议消息及其 turn 边界语义。 */
    public record SourceMessage(
            /** 持久化会话消息的单调递增序号。 */
            long messageSequence,
            /** 来源角色类别，仅 USER 会开启新轮次。 */
            SourceKind kind,
            /** 该持久化行展开出的零到多条 provider-neutral 消息。 */
            List<ContextMessage> messages) {

        /** 固定源消息副本并拒绝无效序号。 */
        public SourceMessage {
            if (messageSequence <= 0L) {
                throw new IllegalArgumentException("messageSequence must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }

        /** 创建开启新问答的 user 来源。 */
        public static SourceMessage user(long sequence, ContextMessage message) {
            return new SourceMessage(sequence, SourceKind.USER, List.of(message));
        }

        /** 创建可展开多次模型调用、但不携带完成态的 assistant 来源。 */
        public static SourceMessage assistant(long sequence, List<ContextMessage> messages) {
            return new SourceMessage(sequence, SourceKind.ASSISTANT, messages);
        }

        /** 创建不改变问答边界的普通协议来源。 */
        public static SourceMessage protocol(long sequence, ContextMessage message) {
            return new SourceMessage(sequence, SourceKind.PROTOCOL, List.of(message));
        }
    }

    /** 持久化来源对问答边界的三种影响。 */
    public enum SourceKind {
        USER,
        ASSISTANT,
        PROTOCOL
    }
}
