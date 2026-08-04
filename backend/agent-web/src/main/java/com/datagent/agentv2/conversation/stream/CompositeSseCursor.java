package com.datagent.agentv2.conversation.stream;

/** 服务端连接内部记录会话持久事件和当前 Agent 执行的流位置。 */
public record CompositeSseCursor(
        long executionEventSequence,
        long executionAttemptNumber,
        long modelInvocationSequence,
        long outputDeltaSequence) {

    public static final CompositeSseCursor ZERO = new CompositeSseCursor(0, 0, 0, 0);

    public CompositeSseCursor {
        if (executionEventSequence < 0
                || executionAttemptNumber < 0
                || modelInvocationSequence < 0
                || outputDeltaSequence < 0) {
            throw new IllegalArgumentException("SSE cursor positions must not be negative");
        }
    }

    public CompositeSseCursor withEvent(long executionEventSequence) {
        return new CompositeSseCursor(
                executionEventSequence,
                executionAttemptNumber,
                modelInvocationSequence,
                outputDeltaSequence);
    }

    public CompositeSseCursor withStream(
            long executionAttemptNumber, long modelInvocationSequence, long outputDeltaSequence) {
        return new CompositeSseCursor(
                executionEventSequence,
                executionAttemptNumber,
                modelInvocationSequence,
                outputDeltaSequence);
    }
}
