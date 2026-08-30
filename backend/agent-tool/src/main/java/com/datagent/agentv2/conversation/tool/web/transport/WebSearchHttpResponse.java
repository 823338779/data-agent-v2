package com.datagent.agentv2.conversation.tool.web.transport;

import java.util.List;
import java.util.Map;

/** 原样承载 HTTP 状态、响应头和正文；分类与 JSON 解析由 Provider 完成。 */
public record WebSearchHttpResponse(
        int statusCode, Map<String, List<String>> headers, String body) {

    public WebSearchHttpResponse {
        headers = Map.copyOf(headers);
    }
}
