package com.datagent.agentv2.development.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** 公开开发请求拒绝未知属性，避免客户端误以为可覆盖服务端固定目标。 */
public interface StrictDevelopmentRequest {
    @JsonAnySetter
    default void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("请求包含未知字段");
    }
}
