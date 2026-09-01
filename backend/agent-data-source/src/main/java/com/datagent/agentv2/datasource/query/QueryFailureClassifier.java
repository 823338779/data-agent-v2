package com.datagent.agentv2.datasource.query;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

/** 只使用异常类型和 SQLState 类别判断，不向上层透传厂商原始文本。 */
public final class QueryFailureClassifier {

    public QueryFailureCategory classify(SQLException failure) {
        if (failure instanceof SQLTimeoutException) return QueryFailureCategory.TIMEOUT;
        if (failure instanceof SQLTransientConnectionException)
            return QueryFailureCategory.TRANSIENT_CONNECTION;
        if (failure instanceof SQLSyntaxErrorException)
            return QueryFailureCategory.SYNTAX_OR_SCHEMA;
        if (failure instanceof SQLNonTransientConnectionException)
            return QueryFailureCategory.FATAL;
        String state = failure.getSQLState();
        if (state != null && state.length() >= 2) {
            return switch (state.substring(0, 2)) {
                case "08" -> QueryFailureCategory.TRANSIENT_CONNECTION;
                case "42" -> QueryFailureCategory.SYNTAX_OR_SCHEMA;
                case "HY" ->
                        state.equals("HYT00") || state.equals("HYT01")
                                ? QueryFailureCategory.TIMEOUT
                                : QueryFailureCategory.FATAL;
                default -> QueryFailureCategory.FATAL;
            };
        }
        return QueryFailureCategory.FATAL;
    }
}
