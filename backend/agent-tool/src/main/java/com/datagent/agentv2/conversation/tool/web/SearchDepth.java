package com.datagent.agentv2.conversation.tool.web;

/** Tavily 支持的检索深度。 */
public enum SearchDepth {
    BASIC("basic"),
    ADVANCED("advanced");

    private final String value;

    SearchDepth(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

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
