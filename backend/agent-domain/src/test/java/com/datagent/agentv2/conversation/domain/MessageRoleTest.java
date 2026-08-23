package com.datagent.agentv2.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MessageRoleTest {

    @Test
    void mapsPersistedValuesToBusinessRoles() {
        assertThat(MessageRole.fromValue("user")).isEqualTo(MessageRole.USER);
        assertThat(MessageRole.fromValue("assistant")).isEqualTo(MessageRole.ASSISTANT);
        assertThat(MessageRole.USER.value()).isEqualTo("user");
        assertThat(MessageRole.ASSISTANT.value()).isEqualTo("assistant");
    }

    @Test
    void excludesModelProtocolOnlyRoles() {
        assertThatIllegalArgumentException().isThrownBy(() -> MessageRole.fromValue("system"));
        assertThatIllegalArgumentException().isThrownBy(() -> MessageRole.fromValue("tool"));
    }
}
