package com.datagent.agentv2.memory;

import java.util.Objects;

/** 可安全穿过传输边界的记忆异常；消息不得携带用户正文、证据或数据库细节。 */
public final class MemoryException extends RuntimeException {
    private final MemoryStatus code;
    private final String safeMessage;

    public MemoryException(MemoryStatus code, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.code = Objects.requireNonNull(code, "code");
        this.safeMessage = safeMessage;
    }

    public MemoryStatus code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
