package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 租户与固定用户账号共用的启停状态。 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ActivationStatus {
    ACTIVE("active"),
    DISABLED("disabled");

    /** 数据库存储的稳定小写值。 */
    @EnumValue private final String value;
}
