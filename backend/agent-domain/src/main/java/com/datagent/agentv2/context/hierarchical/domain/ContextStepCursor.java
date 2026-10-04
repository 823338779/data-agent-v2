package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** 会话内的稳定步骤位置；只有真实消息封口后才能使用 MESSAGE_END。 */
public record ContextStepCursor(
        /** 真实源消息序号；零仅用于空历史起点。 */
        long messageSequence,
        /** 同消息内先消息起点，再调用终点，最后消息终点。 */
        PositionKind positionKind,
        /** 仅调用终点使用正数，其他位置固定为零。 */
        long modelInvocationSequence)
        implements Comparable<ContextStepCursor> {

    public static final ContextStepCursor ORIGIN =
            new ContextStepCursor(0, PositionKind.MESSAGE_START, 0);

    public ContextStepCursor {
        Objects.requireNonNull(positionKind, "positionKind");
        if (messageSequence < 0
                || (positionKind == PositionKind.INVOCATION_END
                        ? modelInvocationSequence <= 0
                        : modelInvocationSequence != 0)
                || (messageSequence == 0 && positionKind != PositionKind.MESSAGE_START)) {
            throw new IllegalArgumentException("Invalid context step cursor");
        }
    }

    @Override
    public int compareTo(ContextStepCursor other) {
        int message = Long.compare(messageSequence, other.messageSequence);
        if (message != 0) {
            return message;
        }
        int kind = positionKind.compareTo(other.positionKind);
        return kind != 0
                ? kind
                : Long.compare(modelInvocationSequence, other.modelInvocationSequence);
    }

    /** 固定排序属于持久化协议，禁止按随机业务 ID 排序。 */
    public enum PositionKind {
        MESSAGE_START,
        INVOCATION_END,
        MESSAGE_END
    }
}
