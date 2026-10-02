package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.ToolExecutionResult;
import com.datagent.agentv2.conversation.tool.ToolFailureSanitizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MemoryToolFailureContractTest {

    @Test
    void structuredBusinessFailureRemainsFailedAndDefensivelyCopiesDetails() {
        ObjectMapper json = JsonMapper.builder().build();
        var details = json.createObjectNode().put("status", "QUOTA_EXCEEDED").put("used", 800);
        var failure = new ToolBusinessException("QUOTA_EXCEEDED", "个人画像容量已满", details);
        details.put("status", "CREATED");
        ((ObjectNode) failure.details()).put("status", "UPDATED");

        ToolExecutionResult result = new ToolFailureSanitizer(json).sanitize(failure);

        assertThat(result.status()).isEqualTo(ToolExecutionResult.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(result.content().path("status").asString()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(result.content().path("used").asInt()).isEqualTo(800);
    }
}
