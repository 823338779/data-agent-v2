package com.datagent.agentv2.skill;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class GitSkillTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.datagent.agentv2.skill",
            excludeFilters =
                    @ComponentScan.Filter(
                            type = org.springframework.context.annotation.FilterType.REGEX,
                            pattern = {
                                ".*tool.*",
                                ".*runtime.*",
                                ".*SkillCatalogStartupValidator"
                            }))
    static class Scan {}

    @Test
    void enabledCatalogIncludesGitWithItsToolDependencies() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scan.class)
                .withPropertyValues("data-agent.git.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            var catalog = ctx.getBean(BundledSkillCatalog.class);
                            assertThat(catalog.bundlesInOrder())
                                    .extracting(b -> b.descriptor().logicalId())
                                    .contains("git-repository-maintenance");
                            assertThat(
                                            catalog.require("git-repository-maintenance")
                                                    .descriptor()
                                                    .requiredTools())
                                    .hasSize(6)
                                    .contains("development_save")
                                    .doesNotContain("git_change_prepare", "git_workspace_prepare")
                                    .doesNotContain("git_confirm");
                        });
    }

    @Test
    void disabledCatalogHasNoDanglingResourceOrDefinition() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scan.class)
                .withPropertyValues("data-agent.git.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(BundledSkillCatalog.class).bundlesInOrder())
                                    .extracting(b -> b.descriptor().logicalId())
                                    .containsExactly("web-research");
                        });
    }
}
