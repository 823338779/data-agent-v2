package com.datagent.agentv2.conversation.model;

import java.util.Objects;

public record ModelOutputDelta(Kind kind, String content) {

    public ModelOutputDelta {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
    }

    public static ModelOutputDelta reasoning(String content) {
        return new ModelOutputDelta(Kind.REASONING, content);
    }

    public static ModelOutputDelta answer(String content) {
        return new ModelOutputDelta(Kind.ANSWER, content);
    }

    public enum Kind {
        REASONING,
        ANSWER
    }
}
