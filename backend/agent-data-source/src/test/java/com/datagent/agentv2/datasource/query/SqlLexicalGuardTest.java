package com.datagent.agentv2.datasource.query;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlLexicalGuardTest {

    private final SqlLexicalGuard guard = new SqlLexicalGuard();

    @Test
    void allowsCommentMarkersInsideStringLiteral() {
        assertThatCode(() -> guard.validate("select '--not-comment', '/*safe*/' from fact_orders"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCommentsMultipleStatementsAndUnterminatedQuotes() {
        assertThatThrownBy(() -> guard.validate("select * from fact_orders -- comment"))
                .isInstanceOf(SqlPolicyException.class);
        assertThatThrownBy(() -> guard.validate("select 1; drop table fact_orders"))
                .isInstanceOf(SqlPolicyException.class);
        assertThatThrownBy(() -> guard.validate("select 'broken"))
                .isInstanceOf(SqlPolicyException.class);
    }
}
