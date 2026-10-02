package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.config.DbtConfiguration;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.port.GitSessionResolver;
import com.datagent.agentv2.persistence.service.dbt.DbtValidationPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 仅查询装配不依赖 Git 工作区，关闭能力不要求远端配置。 */
class DbtAssemblyTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            DbtConfiguration.class,
                            com.datagent.agentv2.skill.builtin.DbtDataAnalysisSkillDefinition.class,
                            com.datagent.agentv2.skill.builtin.DbtYamlMaintenanceSkillDefinition
                                    .class)
                    .withBean(ObjectMapper.class, JsonMapper::new);

    @Test
    void gitOnlyExposesNoDbtSkillsOrTools() {
        runner.withPropertyValues("data-agent.git.enabled=true")
                .run(
                        context -> {
                            assertThat(context.getBeansOfType(AgentTool.class)).isEmpty();
                            assertThat(
                                            context.getBeansOfType(
                                                    com.datagent.agentv2.skill.SkillDefinition
                                                            .class))
                                    .isEmpty();
                        });
    }

    @Test
    void disabledNeedsNoConfiguration() {
        runner.run(context -> assertThat(context.getBeansOfType(AgentTool.class)).isEmpty());
    }

    @Test
    void analysisStartsOfflineWithoutGit() {
        runner.withPropertyValues(
                        "data-agent.dbt.enabled=true",
                        "data-agent.dbt.base-url=http://localhost:1",
                        "data-agent.dbt.project-id=sample")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(AgentTool.class)).hasSize(6);
                        });
    }

    @Test
    void maintenanceAddsTwoToolsOnlyWithGit() {
        runner.withPropertyValues(
                        "data-agent.dbt.enabled=true",
                        "data-agent.git.enabled=true",
                        "data-agent.dbt.base-url=http://localhost:1",
                        "data-agent.dbt.project-id=sample")
                .withBean(GitWorkspaceService.class, () -> mock(GitWorkspaceService.class))
                .withBean(GitSessionResolver.class, () -> execution -> "session")
                .withBean(
                        DbtValidationPersistenceService.class,
                        () -> mock(DbtValidationPersistenceService.class))
                .withBean(
                        com.datagent.agentv2.persistence.mapper.dbt.DbtValidationMapper.class,
                        () ->
                                mock(
                                        com.datagent.agentv2.persistence.mapper.dbt
                                                .DbtValidationMapper.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(AgentTool.class)).hasSize(8);
                        });
    }
}
