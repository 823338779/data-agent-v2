package com.datagent.agentv2.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** 个人长记忆的稳定分类；持久化和接口只使用这里声明的两个值。 */
public enum MemoryKind {
    PERSON_PROFILE("person_profile"),
    COLLABORATION_PREFERENCE("collaboration_preference");

    private final String value;

    MemoryKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemoryKind parse(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知记忆分类"));
    }
}
