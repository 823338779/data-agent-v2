package com.datagent.agentv2.conversation.stream;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** EventSource id 同时记录会话持久事件和当前 Agent 执行的流位置。 */
public record CompositeSseCursor(
        long executionEventSequence,
        long executionAttemptNumber,
        long modelInvocationSequence,
        long outputDeltaSequence) {

    public static final CompositeSseCursor ZERO = new CompositeSseCursor(0, 0, 0, 0);
    private static final Pattern ENCODED =
            Pattern.compile("^e=([0-9]+);a=([0-9]+);t=([0-9]+);s=([0-9]+)$");

    public CompositeSseCursor {
        if (executionEventSequence < 0
                || executionAttemptNumber < 0
                || modelInvocationSequence < 0
                || outputDeltaSequence < 0) {
            throw new IllegalArgumentException("SSE cursor positions must not be negative");
        }
    }

    public String encode() {
        return "e="
                + executionEventSequence
                + ";a="
                + executionAttemptNumber
                + ";t="
                + modelInvocationSequence
                + ";s="
                + outputDeltaSequence;
    }

    public static CompositeSseCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return ZERO;
        }
        Matcher matcher = ENCODED.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid Last-Event-ID composite cursor");
        }
        return new CompositeSseCursor(
                Long.parseLong(matcher.group(1)),
                Long.parseLong(matcher.group(2)),
                Long.parseLong(matcher.group(3)),
                Long.parseLong(matcher.group(4)));
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
