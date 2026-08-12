package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/** 上下文准备已经通过 execution control 端口收敛后的稳定终止信号。 */
public class ContextPreparationException extends RuntimeException {

    private final String failureCode;

    public ContextPreparationException(String failureCode, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
    }

    public String failureCode() {
        return failureCode;
    }
}
