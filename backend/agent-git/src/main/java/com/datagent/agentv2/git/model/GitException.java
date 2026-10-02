package com.datagent.agentv2.git.model;

import lombok.Getter;
import lombok.experimental.Accessors;

/** 对外稳定的 Git 能力异常；code 用于上层映射状态，消息仅保留脱敏后的诊断。 */
@Getter
@Accessors(fluent = true)
public final class GitException extends RuntimeException {
    private final String code;

    public GitException(String code, String message) {
        super(message);
        this.code = code;
    }

    public GitException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
