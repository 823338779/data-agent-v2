package com.datagent.agentv2.persistence.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.persistence.entity.MessageUiBlockEntity;
import com.datagent.agentv2.persistence.json.MessageUiBlockJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.JsonNode;

class MessageUiBlockPersistenceMapperTest {

    @Test
    void restoresGitCardPayloadAsJsonObject() {
        try (var context =
                new AnnotationConfigApplicationContext(
                        MessageUiBlockPersistenceMapperImpl.class, MessageUiBlockJsonCodec.class)) {
            var entity = new MessageUiBlockEntity();
            entity.setUiBlockId("block-1");
            entity.setConversationMessageId("message-1");
            entity.setType("git-change-card");
            entity.setSchemaVersion(1);
            entity.setVariant("default");
            entity.setPayload("{\"changeId\":\"change-1\",\"sessionId\":\"session-1\"}");
            entity.setDisplayOrder(2);

            var block = context.getBean(MessageUiBlockPersistenceMapper.class).toModel(entity);

            assertThat(block.uiBlockId()).isEqualTo("block-1");
            assertThat(block.conversationMessageId()).isEqualTo("message-1");
            assertThat(block.type()).isEqualTo("git-change-card");
            assertThat(block.schemaVersion()).isEqualTo(1);
            assertThat(block.displayOrder()).isEqualTo(2);
            assertThat(block.payload()).isInstanceOf(JsonNode.class);
            assertThat(((JsonNode) block.payload()).get("changeId").asText()).isEqualTo("change-1");
        }
    }
}
