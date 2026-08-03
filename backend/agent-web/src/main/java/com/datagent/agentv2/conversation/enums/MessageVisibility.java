package com.datagent.agentv2.conversation.enums;

/** 会话消息对外展示范围。 */
public enum MessageVisibility {
    USER("user"),
    INTERNAL("internal");

    private final String value;

    MessageVisibility(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
