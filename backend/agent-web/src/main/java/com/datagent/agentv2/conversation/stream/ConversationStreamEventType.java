package com.datagent.agentv2.conversation.stream;

import com.fasterxml.jackson.annotation.JsonValue;

/** 会话流中不属于执行生命周期或模型增量的事件类型。 */
public enum ConversationStreamEventType {
    SNAPSHOT("stream_snapshot");

    private final String value;

    ConversationStreamEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
