package com.datagent.agentv2.persistence;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({MybatisPlusConfiguration.class, AuditMetaObjectHandler.class})
public class AgentPersistenceTestApplication {}
