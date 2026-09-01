package com.datagent.agentv2.datasource.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StarRocksSqlPolicyValidatorTest {

    private final StarRocksSqlPolicyValidator validator = new StarRocksSqlPolicyValidator();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "select * from fact_orders; drop table fact_orders",
                "select /*+ SET_VAR(query_timeout=9999) */ * from fact_orders",
                "select * from files('s3://bucket/data')",
                "show processlist",
                "set query_timeout = 9999",
                "insert into audit select * from fact_orders",
                "select * from secret_table",
                "select * from fact_orders",
                "select fact_orders.* from fact_orders"
            })
    void rejectsUnsafeOrUnauthorizedSql(String sql) {
        assertThatThrownBy(() -> validator.validate(sql, ordersScope()))
                .isInstanceOf(SqlPolicyException.class);
    }

    @Test
    void acceptsApprovedSelectAndRewritesLimit() {
        ValidatedSql sql =
                validator.validate(
                        "select region_code, sum(paid_amount) from fact_orders group by region_code limit 9999",
                        ordersScope());
        assertThat(sql.value()).containsIgnoringCase("LIMIT 100");
    }

    @Test
    void acceptsDeclaredJoinAndRejectsAnotherJoinCondition() {
        QueryAccessScope scope =
                new QueryAccessScope(
                        Set.of("fact_orders", "dim_region"),
                        Map.of(
                                "fact_orders", Set.of("region_code", "paid_amount"),
                                "dim_region", Set.of("region_code", "region_name")),
                        Set.of(
                                new QueryAccessScope.AllowedJoin(
                                        "fact_orders", "region_code", "dim_region", "region_code")),
                        100);
        assertThat(
                        validator
                                .validate(
                                        "select r.region_name, sum(o.paid_amount) from fact_orders o "
                                                + "left join dim_region r on o.region_code = r.region_code group by r.region_name",
                                        scope)
                                .value())
                .containsIgnoringCase("LIMIT 100");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "select r.region_name from fact_orders o join dim_region r on o.paid_amount = r.region_code",
                                        scope))
                .isInstanceOf(SqlPolicyException.class)
                .extracting("reason")
                .isEqualTo(SqlPolicyException.Reason.JOIN_NOT_ALLOWED);
    }

    private static QueryAccessScope ordersScope() {
        return new QueryAccessScope(
                Set.of("fact_orders"),
                Map.of("fact_orders", Set.of("region_code", "paid_amount")),
                Set.of(),
                100);
    }
}
