package com.datagent.agentv2.development.model;

import lombok.Getter;

/** 稳定业务错误，只携带受控诊断，不携带 Git 或数据库原始异常正文。 */
@Getter
public final class DevelopmentException extends RuntimeException {
    private final String code;
    private final int status;

    public DevelopmentException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
