package com.datagent.agentv2.conversation.tool.web;

/** 接收不含查询词、Header、Body 和凭据的搜索遥测。 */
@FunctionalInterface
public interface WebSearchTelemetry {

    void record(WebSearchTelemetryEvent event);
}
