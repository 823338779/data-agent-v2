package com.datagent.agentv2.datasource.config;

import com.datagent.agentv2.datasource.query.QueryFailureClassifier;
import com.datagent.agentv2.datasource.query.StarRocksQueryGateway;
import com.datagent.agentv2.datasource.query.StarRocksSqlPolicyValidator;
import com.datagent.agentv2.datasource.semantic.SemanticCatalog;
import com.datagent.agentv2.datasource.semantic.SemanticCatalogLoader;
import com.datagent.agentv2.datasource.semantic.SemanticCatalogValidator;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSearchService;
import com.datagent.agentv2.datasource.semantic.StarRocksMetadataReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 单 StarRocks 数据源自动装配；连接和语义不一致均在启动期失败。 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "data-agent.workflow", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StarRocksDataSourceProperties.class)
public class StarRocksDataSourceConfiguration {

    @Bean(destroyMethod = "close")
    HikariDataSource starRocksDataSource(StarRocksDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("data-agent-starrocks");
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setReadOnly(true);
        config.setConnectionTimeout(properties.connectTimeout().toMillis());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(properties.connectTimeout().toMillis());
        return new HikariDataSource(config);
    }

    @Bean
    SemanticCatalog semanticCatalog(
            @Qualifier("starRocksDataSource") DataSource starRocksDataSource,
            StarRocksDataSourceProperties properties) {
        var metadata =
                new StarRocksMetadataReader(
                                starRocksDataSource,
                                properties.defaultCatalog(),
                                properties.defaultDatabase())
                        .read();
        var document = new SemanticCatalogLoader().load(properties.semanticConfig());
        return new SemanticCatalogValidator().validate(document, metadata);
    }

    @Bean
    SemanticResourceSearchService semanticResourceSearchService(SemanticCatalog catalog) {
        return new SemanticResourceSearchService(catalog);
    }

    @Bean
    StarRocksSqlPolicyValidator starRocksSqlPolicyValidator() {
        return new StarRocksSqlPolicyValidator();
    }

    @Bean
    QueryFailureClassifier queryFailureClassifier() {
        return new QueryFailureClassifier();
    }

    @Bean
    StarRocksQueryGateway starRocksQueryGateway(
            @Qualifier("starRocksDataSource") DataSource starRocksDataSource,
            StarRocksDataSourceProperties properties,
            QueryFailureClassifier failureClassifier) {
        return new StarRocksQueryGateway(starRocksDataSource, properties, failureClassifier);
    }
}
