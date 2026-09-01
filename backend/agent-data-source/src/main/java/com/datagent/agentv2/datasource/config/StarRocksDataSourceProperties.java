package com.datagent.agentv2.datasource.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;

/** 单一 StarRocks 数据源的服务端配置；该对象不允许携带运行时数据源选择标识。 */
@ConfigurationProperties("data-agent.datasource")
public record StarRocksDataSourceProperties(
        String jdbcUrl,
        String username,
        String password,
        String defaultCatalog,
        String defaultDatabase,
        Duration connectTimeout,
        Duration queryTimeout,
        int maxRows,
        @DataSizeUnit(DataUnit.BYTES) DataSize maxResultBytes,
        Resource semanticConfig) {

    /** 完成绑定后统一校验，错误信息刻意不包含连接串和密码。 */
    public StarRocksDataSourceProperties {
        requireText(jdbcUrl, "StarRocks JDBC 地址未配置");
        requireText(username, "StarRocks 用户名未配置");
        requireText(defaultCatalog, "StarRocks 默认 Catalog 未配置");
        requireText(defaultDatabase, "StarRocks 默认数据库未配置");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("StarRocks 连接超时必须大于 0");
        }
        if (queryTimeout == null || queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("StarRocks 查询超时必须大于 0");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("StarRocks 最大返回行数必须大于 0");
        }
        if (maxResultBytes == null || maxResultBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("StarRocks 最大结果字节数必须大于 0");
        }
        if (semanticConfig == null) {
            throw new IllegalArgumentException("StarRocks 语义配置未配置");
        }
    }

    public static StarRocksDataSourceProperties checked(
            String jdbcUrl,
            String username,
            String password,
            String defaultCatalog,
            String defaultDatabase,
            Duration connectTimeout,
            Duration queryTimeout,
            int maxRows,
            DataSize maxResultBytes,
            Resource semanticConfig) {
        return new StarRocksDataSourceProperties(
                jdbcUrl,
                username,
                password,
                defaultCatalog,
                defaultDatabase,
                connectTimeout,
                queryTimeout,
                maxRows,
                maxResultBytes,
                semanticConfig);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
