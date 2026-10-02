package com.datagent.agentv2.skill;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** 从应用 Classpath 中发现 bundled Skill 主文件。 */
@Component
@RequiredArgsConstructor
public final class ClasspathSkillResourceSource implements SkillResourceSource {
    private static final String DBT_ANALYSIS = "skills/dbt-data-analysis/SKILL.md";
    private static final String DBT_MAINTENANCE = "skills/dbt-yaml-maintenance/SKILL.md";
    private static final String DBT_ENABLED = "data-agent.dbt.enabled";
    private static final String GIT_ENABLED = "data-agent.git.enabled";
    private final Environment environment;

    @Override
    public Map<String, byte[]> load() {
        try {
            Map<String, byte[]> loaded = new LinkedHashMap<>();
            Resource[] resources =
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:skills/*/SKILL.md");
            for (Resource resource : resources) {
                String uri = resource.getURI().toString();
                int start = uri.lastIndexOf("skills/");
                String path = uri.substring(start);
                // 可选资源与对应定义的开关保持一致，其他未知资源仍由目录严格拒绝。
                if (path.equals("skills/git-repository-maintenance/SKILL.md")
                        && !environment.getProperty(
                                "data-agent.git.enabled", Boolean.class, false)) {
                    continue;
                }
                boolean dbtEnabled = environment.getProperty(DBT_ENABLED, Boolean.class, false);
                boolean gitEnabled = environment.getProperty(GIT_ENABLED, Boolean.class, false);
                if (path.equals(DBT_ANALYSIS) && !dbtEnabled
                        || path.equals(DBT_MAINTENANCE) && !(dbtEnabled && gitEnabled)) {
                    continue;
                }
                try (var input = resource.getInputStream()) {
                    if (loaded.put(path, input.readAllBytes()) != null) {
                        throw new IllegalStateException("Duplicate skill resource: " + path);
                    }
                }
            }
            return Map.copyOf(loaded);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to load bundled skills", failure);
        }
    }
}
