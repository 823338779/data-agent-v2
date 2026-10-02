package com.datagent.agentv2.conversation.enums;

/** 对外安全暴露的工具失败分类。 */
@lombok.RequiredArgsConstructor
public enum ToolFailureCode {
    INVALID_ARGUMENTS("invalid_arguments"),
    ACCESS_DENIED("access_denied"),
    TIMEOUT("tool_timeout"),
    TOOL_ERROR("tool_error");

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private final String value;
}
