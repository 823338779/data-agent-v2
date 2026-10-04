package com.datagent.agentv2.dbt.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 固定服务与项目绑定，只校验本地配置，启动不访问远端。
 *
 * @param projectDirectory Git 仓库内的 dbt 子目录，必须与服务 projectSubdir 一致
 * @param connectTimeout 建连截止时间
 * @param requestTimeout 覆盖完整响应读取的总截止时间
 * @param branchServiceToken 部署级分支写凭据，不得暴露给模型或日志；空值禁用分支写入
 */
@ConfigurationProperties("data-agent.dbt")
public record DbtProperties(
        @DefaultValue("false") boolean enabled,
        URI baseUrl,
        String projectId,
        @DefaultValue(".") String projectDirectory,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration requestTimeout,
        String branchServiceToken) {
    /** 保留旧线上分析客户端的构造方式，未提供部署凭据时不能写分支。 */
    public DbtProperties(
            boolean enabled,
            URI baseUrl,
            String projectId,
            String projectDirectory,
            Duration connectTimeout,
            Duration requestTimeout) {
        this(enabled, baseUrl, projectId, projectDirectory, connectTimeout, requestTimeout, null);
    }

    @ConstructorBinding
    public DbtProperties {
        if (branchServiceToken != null
                && (branchServiceToken.indexOf('\r') >= 0
                        || branchServiceToken.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("部署凭据不能包含换行");
        }
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

    /** record 默认输出会泄漏凭据，此处只保留安全的类型标签。 */
    @Override
    public String toString() {
        return SAFE_DESCRIPTION;
    }

    private static final String SAFE_DESCRIPTION = "DbtProperties[redacted]";
}
