package com.datagent.agentv2.conversation.tool.web;

import java.util.List;

/** 搜索供应商的窄适配边界。 */
public interface WebSearchProvider {

    String id();

    List<WebSearchResult> search(WebSearchQuery query)
            throws WebSearchException, InterruptedException;
}
