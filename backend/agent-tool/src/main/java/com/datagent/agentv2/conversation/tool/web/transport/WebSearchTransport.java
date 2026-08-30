package com.datagent.agentv2.conversation.tool.web.transport;

import java.io.IOException;

/** 只负责发送规范化 HTTP 请求的窄 Transport。 */
public interface WebSearchTransport {

    WebSearchHttpResponse send(WebSearchHttpRequest request)
            throws IOException, InterruptedException;
}
