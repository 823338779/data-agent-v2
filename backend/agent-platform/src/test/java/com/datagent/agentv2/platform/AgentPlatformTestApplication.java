package com.datagent.agentv2.platform;

import com.datagent.agentv2.platform.config.ClockConfiguration;
import com.datagent.agentv2.platform.config.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.persistence.AuditMetaObjectHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ClockConfiguration.class, MybatisPlusConfiguration.class, AuditMetaObjectHandler.class})
public class AgentPlatformTestApplication {}
