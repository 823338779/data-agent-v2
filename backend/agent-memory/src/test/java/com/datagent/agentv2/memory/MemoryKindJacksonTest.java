package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemoryKindJacksonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void roundTripsStableWireValuesThroughJackson() {
        assertThat(json.writeValueAsString(MemoryKind.PERSON_PROFILE))
                .isEqualTo("\"person_profile\"");
        assertThat(json.readValue("\"person_profile\"", MemoryKind.class))
                .isEqualTo(MemoryKind.PERSON_PROFILE);
        assertThat(json.writeValueAsString(MemoryKind.COLLABORATION_PREFERENCE))
                .isEqualTo("\"collaboration_preference\"");
        assertThat(json.readValue("\"collaboration_preference\"", MemoryKind.class))
                .isEqualTo(MemoryKind.COLLABORATION_PREFERENCE);
    }
}
