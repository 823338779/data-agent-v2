package com.datagent.agentv2.conversation.tool.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 只记录安全事件字段的默认搜索遥测。 */
@Component
public final class Slf4jWebSearchTelemetry implements WebSearchTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger("agent.tool.web_search");

    @Override
    public void record(WebSearchTelemetryEvent event) {
        LOG.info(
                "provider={} depth={} durationMillis={} httpStatus={} resultCount={} resultCode={} requestId={}",
                event.provider(),
                event.depth(),
                event.durationMillis(),
                event.httpStatus(),
                event.resultCount(),
                event.resultCode(),
                event.requestId());
    }
}
