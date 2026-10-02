package com.datagent.agentv2.conversation.session;

import com.datagent.agentv2.conversation.domain.MessageUiBlock;

/** 对外消息投影中的受控 UI Block。 */
public record MessageUiBlockProjection(
        String blockId,
        String type,
        /** 与 type 联合决定前端渲染器，必须为正数。 */
        int schemaVersion,
        /** 已注册的展示变体，默认变体为 default。 */
        String variant,
        /** 服务端生成的 JSON 对象，不携带可执行代码或样式。 */
        Object payload,
        /** 同一消息内从零开始的展示次序。 */
        int displayOrder) {

    /** 从已查询的消息卡片组装公开视图，不读取额外关联数据。 */
    public static MessageUiBlockProjection composeFromBlock(MessageUiBlock block) {
        return new MessageUiBlockProjection(
                block.uiBlockId(),
                block.type(),
                block.schemaVersion(),
                block.variant(),
                block.payload(),
                block.displayOrder());
    }
}
