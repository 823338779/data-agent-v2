package com.datagent.agentv2.datasource.query;

/** SQL 被本地只读白名单拒绝；消息可安全提供给模型用于重新生成。 */
public final class SqlPolicyException extends RuntimeException {

    private final Reason reason;

    public SqlPolicyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MALFORMED,
        MULTI_STATEMENT,
        COMMENT_OR_HINT,
        NOT_SELECT,
        TABLE_NOT_ALLOWED,
        COLUMN_NOT_ALLOWED,
        JOIN_NOT_ALLOWED,
        TABLE_FUNCTION_NOT_ALLOWED
    }
}
