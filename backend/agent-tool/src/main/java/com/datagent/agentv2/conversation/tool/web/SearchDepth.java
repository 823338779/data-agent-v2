package com.datagent.agentv2.conversation.tool.web;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** Tavily 支持的检索深度。 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchDepth {
    BASIC("basic"),
    ADVANCED("advanced");

    private final String value;

    /** 只接受公开协议中的小写值，非法值不得静默降级。 */
    public static SearchDepth fromValue(String value) {
        for (SearchDepth depth : values()) {
            if (depth.value.equals(value)) {
                return depth;
            }
        }
        throw new IllegalArgumentException("search_depth must be basic or advanced");
    }
}
