package com.datagent.agentv2.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BundledSkillCatalogTest {
    @Test
    void loadsWebResearchBundle() {
        BundledSkillCatalog catalog =
                new BundledSkillCatalog(
                        new ClasspathSkillResourceSource(), new SkillFrontmatterParser());
        assertThat(catalog.require("web-research").descriptor().requiredTools())
                .containsExactly("web_search");
        assertThat(catalog.require("web-research").digest()).matches("[0-9a-f]{64}");
    }
}
