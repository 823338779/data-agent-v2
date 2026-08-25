package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 租户与固定用户账号共用的启停状态。 */
public enum ActivationStatus {
    ACTIVE("active"),
    DISABLED("disabled");

    /** 数据库存储的稳定小写值。 */
    @EnumValue private final String value;

    ActivationStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
