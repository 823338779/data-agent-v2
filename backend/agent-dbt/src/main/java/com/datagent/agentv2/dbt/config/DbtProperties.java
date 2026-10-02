package com.datagent.agentv2.dbt.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 固定服务与项目绑定，只校验本地配置，启动不访问远端。
 *
 * @param projectDirectory Git 仓库内的 dbt 子目录，必须与服务 projectSubdir 一致
 * @param connectTimeout 建连截止时间
 * @param requestTimeout 覆盖完整响应读取的总截止时间
 */
@ConfigurationProperties("data-agent.dbt")
public record DbtProperties(
        @DefaultValue("false") boolean enabled,
        URI baseUrl,
        String projectId,
        @DefaultValue(".") String projectDirectory,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration requestTimeout) {
    public DbtProperties {
        if (enabled
                && (baseUrl == null
                        || baseUrl.getHost() == null
                        || baseUrl.getUserInfo() != null
                        || !("http".equals(baseUrl.getScheme())
                                || "https".equals(baseUrl.getScheme()))
                        || baseUrl.getQuery() != null
                        || baseUrl.getFragment() != null
                        || projectId == null
                        || !projectId.matches("[A-Za-z0-9_.-]+")
                        || projectDirectory == null
                        || projectDirectory.isBlank()
                        || projectDirectory.startsWith("/")
                        || projectDirectory.contains("\\")
                        || projectDirectory.contains(":")
                        || projectDirectory.contains("..")
                        || !projectDirectory.equals(".")
                                && java.util.Arrays.stream(projectDirectory.split("/", -1))
                                        .anyMatch(
                                                segment -> segment.isEmpty() || segment.equals("."))
                        || connectTimeout == null
                        || connectTimeout.isNegative()
                        || connectTimeout.isZero()
                        || requestTimeout == null
                        || requestTimeout.isNegative()
                        || requestTimeout.isZero()
                        || requestTimeout.compareTo(Duration.ofSeconds(20)) > 0
                        || connectTimeout.compareTo(requestTimeout) > 0)) {
            throw new IllegalArgumentException("启用 dbt 前必须配置合法固定服务地址、项目和超时");
        }
    }
}
