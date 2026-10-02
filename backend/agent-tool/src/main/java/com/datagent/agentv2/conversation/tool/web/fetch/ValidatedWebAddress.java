package com.datagent.agentv2.conversation.tool.web.fetch;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 当前一跳已通过地址策略的连接目标；Transport 只能使用这一份解析快照，不得再次解析域名。
 *
 * @param uri 原始 URI，保留 Host、TLS SNI 与证书主机名验证所需的域名
 * @param addresses 本跳全部已校验的 IP；不可变且非空，禁止跨重定向复用
 */
record ValidatedWebAddress(URI uri, List<InetAddress> addresses) {
    ValidatedWebAddress {
        uri = Objects.requireNonNull(uri, "uri");
        addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("addresses must not be empty");
        }
    }
}
