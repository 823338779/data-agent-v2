package com.datagent.agentv2.conversation.tool.web.fetch;

import java.net.URI;

/** 发送单次网页 GET 请求；不负责重定向和地址策略。 */
interface WebFetchTransport {
    WebFetchHttpResponse get(URI uri) throws WebFetchException, InterruptedException;
}
