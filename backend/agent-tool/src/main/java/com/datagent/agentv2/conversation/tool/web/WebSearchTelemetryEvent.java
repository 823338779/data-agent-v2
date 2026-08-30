package com.datagent.agentv2.conversation.tool.web;

/**
 * 搜索调用安全遥测。
 *
 * @param provider 供应商标识
 * @param depth 检索深度
 * @param durationMillis 供应商调用耗时，单位毫秒
 * @param httpStatus 已收到响应时的 HTTP 状态；网络失败时为 null
 * @param resultCount 成功解析的上游结果数
 * @param resultCode 稳定分类结果
 * @param requestId 供应商请求标识；不存在时为 null
 */
public record WebSearchTelemetryEvent(
        String provider,
        SearchDepth depth,
        long durationMillis,
        Integer httpStatus,
        int resultCount,
        String resultCode,
        String requestId) {}
