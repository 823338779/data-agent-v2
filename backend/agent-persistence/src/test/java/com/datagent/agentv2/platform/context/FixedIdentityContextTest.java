package com.datagent.agentv2.platform.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedIdentityContextTest {

    @Test
    void exposesTheFixedTenantAndUserWithoutRequestAuthentication() {
        assertThat(TenantContext.requiredTenantId()).isEqualTo(TenantContext.TENANT_ID);
        assertThat(OperatorContext.requiredUserId()).isEqualTo(OperatorContext.USER_ID);
        assertThat(OperatorContext.SUBJECT).isNotBlank();
    }
}
