package com.datagent.agentv2.conversation.model;

import java.util.Objects;

public record ModelDelta(Kind kind, String content) {

    public ModelDelta {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
    }

    public static ModelDelta reasoning(String content) {
        return new ModelDelta(Kind.REASONING, content);
    }

    public static ModelDelta answer(String content) {
        return new ModelDelta(Kind.ANSWER, content);
    }

    public enum Kind {
        REASONING,
        ANSWER
    }
}
