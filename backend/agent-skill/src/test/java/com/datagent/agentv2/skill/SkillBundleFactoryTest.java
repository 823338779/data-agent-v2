package com.datagent.agentv2.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillBundleFactoryTest {

    private static final Map<String, byte[]> RESOURCES =
            Map.of("SKILL.md", "# 测试 Skill\n".getBytes(StandardCharsets.UTF_8));

    @Test
    void digestChangesWhenRequiredToolsChange() {
        SkillBundleFactory factory = new SkillBundleFactory();

        SkillBundle first =
                factory.create(
                        definition("test-skill", "用于验证代码化 Skill 元数据。", List.of("tool-a")),
                        RESOURCES);
        SkillBundle second =
                factory.create(
                        definition("test-skill", "用于验证代码化 Skill 元数据。", List.of("tool-b")),
                        RESOURCES);

        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    @Test
    void digestChangesWhenDescriptionChanges() {
        SkillBundleFactory factory = new SkillBundleFactory();

        SkillBundle first = factory.create(definition("test-skill", "第一版描述", List.of()), RESOURCES);
        SkillBundle second =
                factory.create(definition("test-skill", "第二版描述", List.of()), RESOURCES);

        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    @Test
    void digestChangesWhenLogicalIdChanges() {
        SkillBundleFactory factory = new SkillBundleFactory();

        SkillBundle first = factory.create(definition("first-skill", "相同描述", List.of()), RESOURCES);
        SkillBundle second =
                factory.create(definition("second-skill", "相同描述", List.of()), RESOURCES);

        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    private static SkillDefinition definition(
            String logicalId, String description, List<String> requiredTools) {
        return new SkillDefinition() {
            @Override
            public String logicalId() {
                return logicalId;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<String> requiredTools() {
                return requiredTools;
            }
        };
    }
}
