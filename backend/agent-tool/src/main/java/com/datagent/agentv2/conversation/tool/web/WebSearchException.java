package com.datagent.agentv2.conversation.tool.web;

import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 经过分类且不携带上游响应正文的搜索失败。 */
@Accessors(fluent = true)
@Getter
public final class WebSearchException extends Exception {

    public enum Kind {
        AUTHENTICATION,
        RATE_LIMITED,
        TIMEOUT,
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    private final Kind kind;

    public WebSearchException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public WebSearchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }
}
