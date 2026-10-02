package com.datagent.agentv2.conversation.stream;

/**
 * 服务端连接内部记录会话持久事件和当前 Agent 执行的流位置。
 *
 * @param executionEventSequence 会话内已发送的持久事件水位，0 表示尚未发送
 * @param modelInvocationSequence 当前执行的模型调用序号，0 表示尚未调用
 * @param outputDeltaSequence 当前模型调用内已发送增量水位；切换调用时归零，不能跨调用比较
 */
public record CompositeSseCursor(
        long executionEventSequence, long modelInvocationSequence, long outputDeltaSequence) {

    public static final CompositeSseCursor ZERO = new CompositeSseCursor(0, 0, 0);

    public CompositeSseCursor {
        if (executionEventSequence < 0 || modelInvocationSequence < 0 || outputDeltaSequence < 0) {
            throw new IllegalArgumentException("SSE cursor positions must not be negative");
        }
    }

    public CompositeSseCursor withEvent(long executionEventSequence) {
        return new CompositeSseCursor(
                executionEventSequence, modelInvocationSequence, outputDeltaSequence);
    }

    public CompositeSseCursor withStream(long modelInvocationSequence, long outputDeltaSequence) {
        return new CompositeSseCursor(
                executionEventSequence, modelInvocationSequence, outputDeltaSequence);
    }
}
