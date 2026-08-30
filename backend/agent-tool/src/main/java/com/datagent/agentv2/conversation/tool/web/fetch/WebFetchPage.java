package com.datagent.agentv2.conversation.tool.web.fetch;

import java.net.URI;
import java.util.Objects;

/**
 * 已通过网络和内容类型边界检查的页面。
 *
 * @param finalUri 完成受限重定向后的最终地址
 * @param contentType 上游 Content-Type
 * @param content 最大响应体限制内的原始字节
 */
public record WebFetchPage(URI finalUri, String contentType, byte[] content) {

    public WebFetchPage {
        finalUri = Objects.requireNonNull(finalUri, "finalUri");
        contentType = Objects.requireNonNull(contentType, "contentType");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
