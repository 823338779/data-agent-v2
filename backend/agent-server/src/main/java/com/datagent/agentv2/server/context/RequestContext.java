package com.datagent.agentv2.server.context;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RequestContext {

    public static final String ATTRIBUTE_REQUEST_ID = RequestContext.class.getName() + ".requestId";
    public static final String MDC_REQUEST_ID = "requestId";

    public static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(ATTRIBUTE_REQUEST_ID);
        return requestId instanceof String value ? value : "request_unknown";
    }
}
