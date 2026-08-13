package com.datagent.agentv2.context.hierarchical.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 仅当服务显式选择 hierarchical 策略时装载插件配置。 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "data-agent.context",
        name = "strategy",
        havingValue = "hierarchical")
@EnableConfigurationProperties(HierarchicalContextProperties.class)
public class HierarchicalContextAutoConfiguration {}
