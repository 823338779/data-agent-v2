package com.datagent.agentv2.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ConversationMessageEntityTest {

    @Test
    void acceptsOnlyPersistedConversationRoles() {
        ConversationMessageEntity entity = new ConversationMessageEntity();

        entity.setRole("user");
        assertThat(entity.getRole()).isEqualTo("user");

        entity.setRole("assistant");
        assertThat(entity.getRole()).isEqualTo("assistant");
    }

    @Test
    void rejectsModelProtocolOnlyRoles() {
        ConversationMessageEntity entity = new ConversationMessageEntity();

        assertThatIllegalArgumentException().isThrownBy(() -> entity.setRole("system"));
        assertThatIllegalArgumentException().isThrownBy(() -> entity.setRole("tool"));
        assertThatIllegalArgumentException().isThrownBy(() -> entity.setRole(null));
    }
}
