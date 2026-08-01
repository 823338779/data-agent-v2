package com.datagent.agentv2.auth.security;

import com.datagent.agentv2.platform.context.OperatorIdentity;
import java.security.Principal;
import java.util.Set;

public record AuthenticatedUser(String tenantId, String userId, String subject, Set<String> roles)
        implements Principal {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    @Override
    public String getName() {
        return subject;
    }

    public OperatorIdentity operatorIdentity() {
        return new OperatorIdentity(tenantId, userId, subject, roles);
    }
}
