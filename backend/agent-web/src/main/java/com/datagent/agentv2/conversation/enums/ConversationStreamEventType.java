package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 会话流中不属于执行生命周期或模型增量的事件类型。 */
public enum ConversationStreamEventType {
    /** 数据库权威完整回答投影。 */
    SNAPSHOT("stream_snapshot"),

    /** 模型实时生成的单个回答增量。 */
    ASSISTANT_DELTA("assistant_delta");

    /** 对外 SSE event name 和 JSON 枚举值。 */
    private final String value;

    /** 绑定稳定的线协议字符串。 */
    ConversationStreamEventType(String value) {
        this.value = value;
    }

    /** 返回接口契约使用的稳定事件类型值。 */
    @JsonValue
    public String value() {
        return value;
    }
}
