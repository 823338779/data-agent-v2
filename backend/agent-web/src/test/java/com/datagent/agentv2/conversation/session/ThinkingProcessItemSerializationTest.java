package com.datagent.agentv2.conversation.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ThinkingProcessItemSerializationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-04T01:00:00Z");

    @Test
    void includesThePolymorphicTypeInPublicJson() {
        ThinkingProcessItem item =
                new ModelThinkingProcessItem(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        1L,
                        ThinkingProcessStatus.SUCCEEDED,
                        CREATED_AT,
                        CREATED_AT,
                        CREATED_AT,
                        0L,
                        ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                        "analysis",
                        false,
                        8L,
                        "sha256",
                        1L,
                        "provider",
                        "model");

        String json = JsonMapper.builder().build().writeValueAsString(item);

        assertThat(json).contains("\"type\":\"THINKING\"");
    }
}
