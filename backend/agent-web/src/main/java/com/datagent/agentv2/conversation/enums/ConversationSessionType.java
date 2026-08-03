package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 会话运行模式的有限业务类型。 */
public enum ConversationSessionType {
    INTERACTIVE;

    @JsonValue
    public String value() {
        return name();
    }

    @JsonCreator
    public static ConversationSessionType fromValue(String value) {
        if (INTERACTIVE.name().equals(value)) {
            return INTERACTIVE;
        }
        throw new IllegalArgumentException("Unknown conversation session type: " + value);
    }
}
