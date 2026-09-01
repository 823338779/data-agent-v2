package com.datagent.agentv2.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 进程内 bundled Skill 目录。 */
@Service
public final class BundledSkillCatalog {
    private final Map<String, SkillBundle> bundles;

    public BundledSkillCatalog(SkillResourceSource source, SkillFrontmatterParser parser) {
        Map<String, Map<String, byte[]>> grouped = new LinkedHashMap<>();
        source.load()
                .forEach(
                        (path, bytes) -> {
                            String[] parts = path.split("/", 3);
                            if (parts.length != 3 || !"skills".equals(parts[0]))
                                throw new IllegalArgumentException("Invalid skill resource key");
                            grouped.computeIfAbsent(parts[1], ignored -> new LinkedHashMap<>())
                                    .put(parts[2], bytes);
                        });
        Map<String, SkillBundle> parsed = new LinkedHashMap<>();
        grouped.forEach(
                (id, resources) -> {
                    if (parsed.put(id, parser.parse(id, resources)) != null)
                        throw new IllegalStateException("Duplicate skill: " + id);
                });
        this.bundles = Map.copyOf(parsed);
    }

    public SkillBundle require(String id) {
        SkillBundle bundle = bundles.get(id);
        if (bundle == null) throw new IllegalArgumentException("Unknown skill: " + id);
        return bundle;
    }

    public List<SkillBundle> bundlesInOrder() {
        return new ArrayList<>(bundles.values());
    }
}
