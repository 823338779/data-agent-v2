package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.enums.ConversationSummaryEventType;
import com.datagent.agentv2.conversation.enums.ExecutionControlType;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BusinessEnumSerializationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void finiteBusinessValuesKeepTheirExistingWireRepresentation() {
        // 枚举约束 Java 侧取值，同时通过 JsonValue 固定 REST/SSE 协议字符串。
        assertThat(json.writeValueAsString(ConversationSummaryEventType.SNAPSHOT))
                .isEqualTo("\"conversation_summary_snapshot\"");
        assertThat(json.writeValueAsString(ExecutionEventType.STARTED))
                .isEqualTo("\"execution_started\"");
        assertThat(json.writeValueAsString(ExecutionControlType.CANCEL)).isEqualTo("\"cancel\"");
        assertThat(json.writeValueAsString(ExecutionStatus.RUNNING)).isEqualTo("\"running\"");
        assertThat(json.writeValueAsString(ExecutionPhase.PREPARING_CONTEXT))
                .isEqualTo("\"PREPARING_CONTEXT\"");
        assertThat(json.writeValueAsString(MessageRole.ASSISTANT)).isEqualTo("\"assistant\"");
    }

    @Test
    void finiteBusinessValuesRejectUnknownStrings() {
        assertThat(ExecutionEventType.fromValue("execution_done"))
                .isEqualTo(ExecutionEventType.DONE);
        assertThat(ExecutionStatus.fromValue("running")).isEqualTo(ExecutionStatus.RUNNING);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MessageRole.fromValue("tool"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MessageRole.fromValue("system"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
