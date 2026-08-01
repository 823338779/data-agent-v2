package com.datagent.agentv2.conversation.stream;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** EventSource id 同时记录会话持久事件和当前 Run 流位置。 */
public record CompositeSseCursor(
        long eventSequence, long executionAttempt, long modelTurnSequence, long streamSequence) {

    public static final CompositeSseCursor ZERO = new CompositeSseCursor(0, 0, 0, 0);
    private static final Pattern ENCODED =
            Pattern.compile("^e=([0-9]+);a=([0-9]+);t=([0-9]+);s=([0-9]+)$");

    public CompositeSseCursor {
        if (eventSequence < 0
                || executionAttempt < 0
                || modelTurnSequence < 0
                || streamSequence < 0) {
            throw new IllegalArgumentException("SSE cursor positions must not be negative");
        }
    }

    public String encode() {
        return "e="
                + eventSequence
                + ";a="
                + executionAttempt
                + ";t="
                + modelTurnSequence
                + ";s="
                + streamSequence;
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

    public CompositeSseCursor withEvent(long sequence) {
        return new CompositeSseCursor(
                sequence, executionAttempt, modelTurnSequence, streamSequence);
    }

    public CompositeSseCursor withStream(long attempt, long turn, long sequence) {
        return new CompositeSseCursor(eventSequence, attempt, turn, sequence);
    }
}
