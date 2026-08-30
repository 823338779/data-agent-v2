package com.datagent.agentv2.conversation.tool.web.fetch;

import java.net.URI;

/** 获取一张公开网页的窄边界，供 Tool 门面与测试替身共同使用。 */
@FunctionalInterface
public interface WebPageFetcher {

    WebFetchPage fetch(URI uri) throws WebFetchException, InterruptedException;
}
