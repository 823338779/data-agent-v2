package com.datagent.agentv2.conversation.tool;

/** 对外安全暴露的工具失败分类。 */
public enum ToolFailureCode {
    INVALID_ARGUMENTS("invalid_arguments"),
    ACCESS_DENIED("access_denied"),
    TIMEOUT("timeout"),
    TOOL_ERROR("tool_error");

    private final String value;

    ToolFailureCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
