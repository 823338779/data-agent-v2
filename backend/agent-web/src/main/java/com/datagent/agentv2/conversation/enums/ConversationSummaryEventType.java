package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 用户会话摘要流的事件类型。 */
@lombok.RequiredArgsConstructor
public enum ConversationSummaryEventType {
    SNAPSHOT("conversation_summary_snapshot");

    @lombok.Getter(onMethod_ = @JsonValue)
    @lombok.experimental.Accessors(fluent = true)
    private final String value;
}
