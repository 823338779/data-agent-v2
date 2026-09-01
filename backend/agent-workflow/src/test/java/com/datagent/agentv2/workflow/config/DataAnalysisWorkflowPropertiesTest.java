package com.datagent.agentv2.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class DataAnalysisWorkflowPropertiesTest {

    @Test
    void exposesApprovedDefaultsAndRejectsTaskCountAboveHardCap() {
        assertThat(DataAnalysisWorkflowProperties.defaults().maxQueryTasks()).isEqualTo(5);
        assertThatThrownBy(
                        () ->
                                new DataAnalysisWorkflowProperties(
                                        21,
                                        3,
                                        3,
                                        1,
                                        DataSize.ofKilobytes(64),
                                        30,
                                        DataSize.ofKilobytes(512),
                                        Duration.ofSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
