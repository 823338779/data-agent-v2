package com.datagent.agentv2.conversation.domain;

import java.util.Objects;

/** 与消息绑定的版本化受控 UI 描述；payload 只能由服务端创建。 */
public record MessageUiBlock(
        String uiBlockId,
        String conversationMessageId,
        String type,
        /** 卡片协议版本，必须为正数；前端按 type 与版本共同选择渲染器。 */
        int schemaVersion,
        /** 已注册的展示变体，默认变体为 default。 */
        String variant,
        /** 服务端生成的 JSON 对象，不能包含可执行代码或样式。 */
        Object payload,
        /** 同一消息内的展示次序，从零开始。 */
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
