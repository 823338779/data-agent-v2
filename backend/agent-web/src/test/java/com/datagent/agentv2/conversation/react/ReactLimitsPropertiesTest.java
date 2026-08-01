package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReactLimitsPropertiesTest {

    @Test
    void defaultsReasoningRetentionToThirtyDays() {
        assertThat(new ReactLimitsProperties().getReasoningRetention())
                .isEqualTo(Duration.ofDays(30));
    }
}
