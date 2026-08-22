package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 用户会话摘要流的事件类型。 */
public enum ConversationSummaryEventType {
    SNAPSHOT("conversation_summary_snapshot");

    private final String value;

    ConversationSummaryEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
