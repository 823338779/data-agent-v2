package com.datagent.agentv2.datasource.query;

/** 已通过本地 SQL 策略的不可伪造查询；构造器仅对本包开放。 */
public final class ValidatedSql {

    private final String value;

    ValidatedSql(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
