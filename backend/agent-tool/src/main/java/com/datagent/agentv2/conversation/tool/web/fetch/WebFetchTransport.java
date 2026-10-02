package com.datagent.agentv2.conversation.tool.web.fetch;

/** 只连接已校验地址快照的单次 GET 请求；不重新解析域名，也不自动跟随重定向。 */
interface WebFetchTransport {
    WebFetchHttpResponse get(ValidatedWebAddress target)
            throws WebFetchException, InterruptedException;
}
