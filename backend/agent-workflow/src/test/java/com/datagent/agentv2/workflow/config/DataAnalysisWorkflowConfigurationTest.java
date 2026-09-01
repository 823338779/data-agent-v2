package com.datagent.agentv2.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.workflow.port.WorkflowEventPort;
import com.datagent.agentv2.workflow.runtime.DataAnalysisWorkflowEngine;
import com.datagent.agentv2.workflow.runtime.WorkflowRunRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DataAnalysisWorkflowConfigurationTest {

    @Test
    void keepsTransientRegistryAvailableButDoesNotCreateEngineWhenWorkflowIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataAnalysisWorkflowConfiguration.class))
                .withBean(WorkflowEventPort.class, () -> event -> {})
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("data-agent.workflow.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(WorkflowRunRegistry.class);
                            assertThat(context).doesNotHaveBean(DataAnalysisWorkflowEngine.class);
                        });
    }
}
