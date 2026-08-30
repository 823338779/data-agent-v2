package com.datagent.agentv2.conversation.tool.web;

/**
 * 供应商返回的最小搜索结果。
 *
 * @param title 页面标题；缺失时可为空
 * @param url 页面绝对地址；合法性由 Tool 门面逐条过滤
 * @param snippet 结果摘要；缺失时可为空
 * @param score 供应商相关性分数；未提供时为 null
 */
public record WebSearchResult(String title, String url, String snippet, Double score) {}
