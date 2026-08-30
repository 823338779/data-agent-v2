package com.datagent.agentv2.conversation.tool.web.fetch;

import java.util.Objects;

/** 经过分类且不携带页面正文的网页获取失败。 */
public final class WebFetchException extends Exception {

    public enum Kind {
        BLOCKED,
        TIMEOUT,
        UNAVAILABLE,
        HTTP_ERROR,
        UNSUPPORTED_CONTENT_TYPE,
        RESPONSE_TOO_LARGE,
        INVALID_RESPONSE
    }

    private final Kind kind;

    public WebFetchException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public WebFetchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
