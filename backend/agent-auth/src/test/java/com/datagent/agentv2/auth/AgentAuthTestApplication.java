package com.datagent.agentv2.auth;

import com.datagent.agentv2.persistence.AuditMetaObjectHandler;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.config.ClockConfiguration;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {"com.datagent.agentv2.auth", "com.datagent.agentv2.persistence.service"})
@Import({
    ClockConfiguration.class,
    PrefixedUuidIdGenerator.class,
    MybatisPlusConfiguration.class,
    AuditMetaObjectHandler.class
})
public class AgentAuthTestApplication {}
