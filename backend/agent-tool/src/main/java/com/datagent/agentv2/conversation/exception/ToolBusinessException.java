package com.datagent.agentv2.conversation.exception;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** 工具可安全暴露给模型的业务失败。 */
public final class ToolBusinessException extends Exception {

    private final String code;
    private final String safeMessage;
    private final JsonNode details;

    public ToolBusinessException(String code, String safeMessage) {
        this(code, safeMessage, null);
    }

    public ToolBusinessException(String code, String safeMessage, JsonNode details) {
        super(safeMessage);
        this.code = Objects.requireNonNull(code, "code");
        this.safeMessage = Objects.requireNonNull(safeMessage, "safeMessage");
        this.details = details == null ? null : details.deepCopy();
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    /** 返回可安全提供给模型的结构化失败详情；调用方不能改写异常内部状态。 */
    public JsonNode details() {
        return details == null ? null : details.deepCopy();
    }
}
