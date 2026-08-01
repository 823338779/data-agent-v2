package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
public class AuditFixtureService extends ServiceImpl<AuditFixtureMapper, AuditFixtureEntity> {

    AuditFixtureEntity findBySessionId(String sessionId) {
        return lambdaQuery().eq(AuditFixtureEntity::getSessionId, sessionId).one();
    }
}
