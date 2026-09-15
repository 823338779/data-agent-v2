package com.datagent.agentv2.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.skill.builtin.WebResearchSkillDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class BundledSkillCatalogTest {
    @Test
    void loadsWebResearchRequiredToolsFromCodeDefinition() {
        BundledSkillCatalog catalog =
                new BundledSkillCatalog(
                        new ClasspathSkillResourceSource(
                                new org.springframework.mock.env.MockEnvironment()),
                        new SkillBundleFactory(),
                        List.of(new WebResearchSkillDefinition()));
        SkillBundle bundle = catalog.require("web-research");

        assertThat(bundle.descriptor().description()).isEqualTo("当问题需要最新信息或外部公开资料时使用。");
        assertThat(bundle.descriptor().requiredTools()).containsExactly("web_search", "web_fetch");
        assertThat(bundle.readText("SKILL.md")).startsWith("# 联网研究");
        assertThat(bundle.digest()).matches("[0-9a-f]{64}");
    }
}
