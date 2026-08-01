package com.datagent.agentv2.persistence;

import org.springframework.boot.test.context.TestComponent;

@TestComponent
public class AuditFixtureService
        extends AuditedServiceImpl<AuditFixtureMapper, AuditFixtureEntity> {}
