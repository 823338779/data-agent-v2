package com.datagent.agentv2.persistence;

import com.datagent.agentv2.persistence.config.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.config.ClockConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ClockConfiguration.class, MybatisPlusConfiguration.class, AuditMetaObjectHandler.class})
public class AgentPersistenceTestApplication {}
