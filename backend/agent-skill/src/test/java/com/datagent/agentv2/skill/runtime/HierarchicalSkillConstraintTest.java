package com.datagent.agentv2.skill.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.skill.BundledSkillCatalog;
import com.datagent.agentv2.skill.ClasspathSkillResourceSource;
import com.datagent.agentv2.skill.SkillBundleFactory;
import com.datagent.agentv2.skill.builtin.WebResearchSkillDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HierarchicalSkillConstraintTest {
    private static final String SKILL = "web-research";
    private static final String PATH = "SKILL.md";

    @Test
    void skillConstraintsSurviveRepeatedCompaction() {
        var catalog =
                new BundledSkillCatalog(
                        new ClasspathSkillResourceSource(new MockEnvironment()),
                        new SkillBundleFactory(),
                        List.of(new WebResearchSkillDefinition()));
        var view = new ExecutionSkillView(catalog);
        assertThat(view.readConstraints()).isEmpty();
        var read = view.read(SKILL, PATH);
        view.read(SKILL, PATH);
        assertThat(view.readConstraints()).containsExactly(read);
        assertThat(view.readConstraints().getFirst().content())
                .isEqualTo(catalog.require(SKILL).readText(PATH));
    }
}
