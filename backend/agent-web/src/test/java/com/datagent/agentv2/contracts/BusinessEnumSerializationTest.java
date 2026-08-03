package com.datagent.agentv2.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.activity.ActivityEventType;
import com.datagent.agentv2.conversation.cluster.ExecutionControlType;
import com.datagent.agentv2.conversation.execution.ExecutionEventType;
import com.datagent.agentv2.conversation.execution.ExecutionPhase;
import com.datagent.agentv2.conversation.execution.ExecutionStatus;
import com.datagent.agentv2.conversation.message.MessageRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BusinessEnumSerializationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void finiteBusinessValuesKeepTheirExistingWireRepresentation() {
        // 枚举约束 Java 侧取值，同时通过 JsonValue 保持既有 REST/SSE 协议字符串不变。
        assertThat(json.writeValueAsString(ActivityEventType.SNAPSHOT))
                .isEqualTo("\"activity_snapshot\"");
        assertThat(json.writeValueAsString(ExecutionEventType.STARTED))
                .isEqualTo("\"execution_started\"");
        assertThat(json.writeValueAsString(ExecutionControlType.CANCEL)).isEqualTo("\"cancel\"");
        assertThat(json.writeValueAsString(ExecutionStatus.WAITING_FOR_USER))
                .isEqualTo("\"waiting_for_user\"");
        assertThat(json.writeValueAsString(ExecutionPhase.PREPARING_CONTEXT))
                .isEqualTo("\"PREPARING_CONTEXT\"");
        assertThat(json.writeValueAsString(MessageRole.ASSISTANT)).isEqualTo("\"assistant\"");
    }

    @Test
    void finiteBusinessValuesRejectUnknownStrings() {
        assertThat(ExecutionEventType.fromValue("execution_done"))
                .isEqualTo(ExecutionEventType.DONE);
        assertThat(ExecutionStatus.fromValue("running")).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(MessageRole.fromValue("tool")).isEqualTo(MessageRole.TOOL);
    }
}
