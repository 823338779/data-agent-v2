package com.datagent.agentv2.datasource.query;

/** 引号感知的词法前置检查，阻断多语句、注释和 hint。 */
public final class SqlLexicalGuard {

    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw denied(SqlPolicyException.Reason.MALFORMED, "SQL 不能为空");
        }
        Quote quote = Quote.NONE;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (quote == Quote.SINGLE) {
                if (current == '\'' && next == '\'') {
                    index++;
                } else if (current == '\'') {
                    quote = Quote.NONE;
                }
                continue;
            }
            if (quote == Quote.DOUBLE) {
                if (current == '"' && next == '"') {
                    index++;
                } else if (current == '"') {
                    quote = Quote.NONE;
                }
                continue;
            }
            if (quote == Quote.BACKTICK) {
                if (current == '`' && next == '`') {
                    index++;
                } else if (current == '`') {
                    quote = Quote.NONE;
                }
                continue;
            }
            if (current == '\'') quote = Quote.SINGLE;
            else if (current == '"') quote = Quote.DOUBLE;
            else if (current == '`') quote = Quote.BACKTICK;
            else if (current == ';')
                throw denied(SqlPolicyException.Reason.MULTI_STATEMENT, "SQL 只能包含一条语句");
            else if ((current == '-' && next == '-') || (current == '/' && next == '*')) {
                throw denied(SqlPolicyException.Reason.COMMENT_OR_HINT, "SQL 不允许注释或 Hint");
            }
        }
        if (quote != Quote.NONE) {
            throw denied(SqlPolicyException.Reason.MALFORMED, "SQL 引号未闭合");
        }
    }

    private static SqlPolicyException denied(SqlPolicyException.Reason reason, String message) {
        return new SqlPolicyException(reason, message);
    }

    private enum Quote {
        NONE,
        SINGLE,
        DOUBLE,
        BACKTICK
    }
}
