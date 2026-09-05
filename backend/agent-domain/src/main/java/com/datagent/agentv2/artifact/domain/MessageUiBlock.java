package com.datagent.agentv2.artifact.domain;

import java.util.Objects;

/** 与消息绑定的版本化受控 UI 描述；payload 只能由服务端创建。 */
public record MessageUiBlock(
        String uiBlockId,
        String conversationMessageId,
        String type,
        int schemaVersion,
        String variant,
        Object payload,
        int displayOrder) {

    public MessageUiBlock {
        Objects.requireNonNull(uiBlockId, "uiBlockId");
        Objects.requireNonNull(conversationMessageId, "conversationMessageId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(payload, "payload");
        if (schemaVersion < 1 || displayOrder < 0) {
            throw new IllegalArgumentException(
                    "schemaVersion must be positive and order non-negative");
        }
    }
}
