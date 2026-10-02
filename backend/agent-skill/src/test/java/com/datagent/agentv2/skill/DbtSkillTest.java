package com.datagent.agentv2.skill;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.skill.builtin.*;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

/** 资源与定义必须使用同一开关，关闭能力时不能留下导致启动失败的孤立资源。 */
class DbtSkillTest {
    @ParameterizedTest
    @CsvSource({"false,false,1", "false,true,2", "true,false,2", "true,true,4"})
    void catalogAndDependenciesMatchCapabilityFlags(boolean dbt, boolean git, int count) {
        var environment =
                new MockEnvironment()
                        .withProperty("data-agent.dbt.enabled", "" + dbt)
                        .withProperty("data-agent.git.enabled", "" + git);
        var definitions = new ArrayList<SkillDefinition>();
        definitions.add(new WebResearchSkillDefinition());
        if (git) definitions.add(new GitRepositoryMaintenanceSkillDefinition());
        if (dbt) definitions.add(new DbtDataAnalysisSkillDefinition());
        if (dbt && git) definitions.add(new DbtYamlMaintenanceSkillDefinition());
        var catalog =
                new BundledSkillCatalog(
                        new ClasspathSkillResourceSource(environment),
                        new SkillBundleFactory(),
                        definitions);
        assertThat(catalog.bundlesInOrder()).hasSize(count);
        if (dbt)
            assertThat(catalog.require("dbt-data-analysis").descriptor().requiredTools())
                    .hasSize(6);
        if (dbt && git)
            assertThat(catalog.require("dbt-yaml-maintenance").descriptor().requiredTools())
                    .hasSize(9)
                    .contains("git_change_prepare", "dbt_validation_submit", "dbt_validation_get");
    }
}
