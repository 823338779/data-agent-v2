package com.datagent.agentv2.context.hierarchical.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;

/** 仅当服务显式选择 hierarchical 策略时装载插件配置。 */
@AutoConfiguration
@AutoConfigureAfter(FlywayAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
@EnableConfigurationProperties(HierarchicalContextProperties.class)
@Import({HierarchicalMapperConfiguration.class, HierarchicalFlywayMigrator.class})
public class HierarchicalContextAutoConfiguration {}
