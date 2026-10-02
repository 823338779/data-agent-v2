package com.datagent.agentv2.conversation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** 持久化业务会话消息的有限角色；模型协议中的 system 和 tool 不属于此枚举。 */
@lombok.RequiredArgsConstructor
public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant");

    @lombok.Getter(onMethod_ = @JsonValue)
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    @JsonCreator
    public static MessageRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown message role: " + value));
    }
}
