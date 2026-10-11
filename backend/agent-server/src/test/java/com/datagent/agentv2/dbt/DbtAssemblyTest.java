package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.config.DbtConfiguration;
import com.datagent.agentv2.dbt.port.*;
import com.datagent.agentv2.dbt.tool.DbtAnalysisTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 分析装配不依赖Git或数据库，目标只能通过业务端口取得。 */
class DbtAssemblyTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(DbtConfiguration.class)
                    .withBean(ObjectMapper.class, JsonMapper::new)
                    .withBean(
                            BuildContextResolver.class,
                            () -> (session, execution) -> java.util.UUID.randomUUID())
                    .withBean(AnalysisSessionResolver.class, () -> context -> "session");

    @Test
    void disabledNeedsNoConfiguration() {
        runner.run(context -> assertThat(context.getBeansOfType(AgentTool.class)).isEmpty());
    }

    @Test
    void analysisStartsOfflineWithoutGit() {
        runner.withPropertyValues(
                        "data-agent.dbt.enabled=true", "data-agent.dbt.base-url=http://localhost:1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(AgentTool.class).values())
                                    .extracting(AgentTool::name)
                                    .containsExactlyInAnyOrderElementsOf(DbtAnalysisTool.TOOL_IDS);
                        });
    }

    @Test
    void gitDoesNotAddRetiredValidationTools() {
        runner.withPropertyValues(
                        "data-agent.dbt.enabled=true",
                        "data-agent.git.enabled=true",
                        "data-agent.dbt.base-url=http://localhost:1")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(AgentTool.class).values())
                                    .hasSize(5)
                                    .extracting(AgentTool::name)
                                    .doesNotContain("dbt_query_get");
                        });
    }
}
