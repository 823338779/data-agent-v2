package com.datagent.agentv2.platform.context;

import java.util.Set;

public record OperatorIdentity(String tenantId, String userId, String subject, Set<String> roles) {

    public OperatorIdentity {
        roles = Set.copyOf(roles);
    }
}
