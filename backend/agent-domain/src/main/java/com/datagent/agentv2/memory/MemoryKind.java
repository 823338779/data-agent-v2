package com.datagent.agentv2.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** 个人长记忆的稳定分类；持久化和接口只使用这里声明的两个值。 */
@lombok.RequiredArgsConstructor
public enum MemoryKind {
    PERSON_PROFILE("person_profile"),
    COLLABORATION_PREFERENCE("collaboration_preference");

    @lombok.Getter(onMethod_ = @JsonValue)
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    @JsonCreator
    public static MemoryKind parse(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知记忆分类"));
    }
}
