package com.datagent.agentv2.conversation.exception;

import java.util.Objects;

/** 工具可安全暴露给模型的业务失败。 */
public final class ToolBusinessException extends Exception {

    private final String code;
    private final String safeMessage;

    public ToolBusinessException(String code, String safeMessage) {
        super(safeMessage);
        this.code = Objects.requireNonNull(code, "code");
        this.safeMessage = Objects.requireNonNull(safeMessage, "safeMessage");
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
