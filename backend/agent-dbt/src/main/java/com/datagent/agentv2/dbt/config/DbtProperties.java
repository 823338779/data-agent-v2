package com.datagent.agentv2.dbt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/** 仅固定服务连接；仓库和构建选择来自可信业务上下文，启动不访问远端。 */
@ConfigurationProperties("data-agent.dbt")
public record DbtProperties(
        /** 能力开关。 */
        @DefaultValue("false") boolean enabled,
        /** 受控服务根地址，不携带协议版本前缀。 */
        URI baseUrl,
        /** 连接截止时间。 */
        @DefaultValue("5s") Duration connectTimeout,
        /** 覆盖同步引擎执行与完整响应读取的总期限，默认比引擎30分钟多留1分钟。 */
        @DefaultValue("1860s") Duration requestTimeout,
        /** 所有写接口的服务凭据，不得输出。 */
        String serviceToken) {
    private static final String HTTP = "http", HTTPS = "https", SAFE = "DbtProperties[redacted]";

    public DbtProperties {
        if (serviceToken != null
                && (serviceToken.indexOf('\r') >= 0 || serviceToken.indexOf('\n') >= 0))
            throw new IllegalArgumentException("服务凭据不能包含换行");
        if (enabled
                && (baseUrl == null
                        || baseUrl.getHost() == null
                        || baseUrl.getUserInfo() != null
                        || !(HTTP.equals(baseUrl.getScheme()) || HTTPS.equals(baseUrl.getScheme()))
                        || baseUrl.getQuery() != null
                        || baseUrl.getFragment() != null
                        || connectTimeout == null
                        || connectTimeout.isNegative()
                        || connectTimeout.isZero()
                        || requestTimeout == null
                        || requestTimeout.isNegative()
                        || requestTimeout.isZero()
                        || connectTimeout.compareTo(requestTimeout) > 0))
            throw new IllegalArgumentException("启用dbt前必须配置合法固定服务地址和超时");
    }

    @Override
    public String toString() {
        return SAFE;
    }
}
