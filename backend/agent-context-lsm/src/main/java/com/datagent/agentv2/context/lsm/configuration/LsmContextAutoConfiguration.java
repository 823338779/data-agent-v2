package com.datagent.agentv2.context.lsm.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;

/** 仅当服务明确选择 lsm 策略时装载插件基础设施。 */
@AutoConfiguration
@AutoConfigureAfter(FlywayAutoConfiguration.class)
@ConditionalOnProperty(prefix = "data-agent.context", name = "strategy", havingValue = "lsm")
@EnableConfigurationProperties(LsmContextProperties.class)
@Import({LsmMapperConfiguration.class, LsmFlywayMigrator.class})
public class LsmContextAutoConfiguration {}
