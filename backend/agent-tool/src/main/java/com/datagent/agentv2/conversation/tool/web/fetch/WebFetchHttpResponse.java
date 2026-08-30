package com.datagent.agentv2.conversation.tool.web.fetch;

import java.util.List;
import java.util.Map;

/** 单次、不自动重定向的 HTTP 响应。 */
record WebFetchHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    WebFetchHttpResponse {
        headers = Map.copyOf(headers);
        body = body.clone();
    }
}
