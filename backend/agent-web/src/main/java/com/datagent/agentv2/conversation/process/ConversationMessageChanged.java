package com.datagent.agentv2.conversation.process;

/** 一条中心消息成功更新后的稳定内部通知。 */
public record ConversationMessageChanged(
        /** 消息所属租户标识。 */
        String tenantId,
        /** 消息所属会话标识。 */
        String conversationSessionId,
        /** 被更新的中心消息标识。 */
        String conversationMessageId,
        /** 本次更新完成后的乐观锁版本。 */
        long messageVersion) {}
