package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 用户会话活动流的事件类型。 */
public enum ActivityEventType {
    SNAPSHOT("activity_snapshot");

    private final String value;

    ActivityEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
