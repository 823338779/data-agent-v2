package com.datagent.agentv2.conversation.tool.web;

/**
 * 供应商无关的搜索请求。
 *
 * @param query 已裁剪的公开检索词，不得包含服务端凭据
 * @param limit 最大结果数，范围 1～20
 * @param depth 检索深度
 */
public record WebSearchQuery(String query, int limit, SearchDepth depth) {}
