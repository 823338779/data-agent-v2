package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.RunConstants.INSTANCE_ID_PREFIX;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class RunInstanceIdentity {

    private static final String BLANK_INSTANCE_ID_MESSAGE = "instance id must not be blank";

    private final String id;

    public RunInstanceIdentity() {
        this(INSTANCE_ID_PREFIX + UUID.randomUUID());
    }

    public RunInstanceIdentity(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(BLANK_INSTANCE_ID_MESSAGE);
        }
        this.id = id;
    }

    public String id() {
        return id;
    }
}
