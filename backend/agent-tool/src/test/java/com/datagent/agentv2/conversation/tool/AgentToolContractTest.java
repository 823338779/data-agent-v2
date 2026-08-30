package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import org.junit.jupiter.api.Test;

class AgentToolContractTest {

    @Test
    void businessFailureExposesOnlyStableCodeAndSafeMessage() {
        ToolBusinessException failure =
                new ToolBusinessException("web_search_unavailable", "Search is unavailable");

        assertThat(failure.code()).isEqualTo("web_search_unavailable");
        assertThat(failure.safeMessage()).isEqualTo("Search is unavailable");
        assertThat(failure.getMessage()).isEqualTo("Search is unavailable");
    }
}
