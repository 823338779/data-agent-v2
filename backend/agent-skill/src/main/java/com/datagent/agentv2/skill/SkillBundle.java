package com.datagent.agentv2.skill;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 已装载到内存的不可变 Skill 资源包，读取时从不访问宿主文件系统。 */
@Accessors(fluent = true)
public final class SkillBundle {
    @Getter private final SkillDescriptor descriptor;
    @Getter private final String digest;
    private final Map<String, byte[]> resources;

    public SkillBundle(SkillDescriptor descriptor, String digest, Map<String, byte[]> resources) {
        this.descriptor = descriptor;
        this.digest = digest;
        Map<String, byte[]> copied = new LinkedHashMap<>();
        resources.forEach((path, bytes) -> copied.put(path, bytes.clone()));
        this.resources = Map.copyOf(copied);
    }

    public String readText(String path) {
        String normalized = normalize(path);
        byte[] content = resources.get(normalized);
        if (content == null) {
            throw new IllegalArgumentException("Unknown skill resource: " + normalized);
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    static String normalize(String path) {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.contains("\\")
                || path.contains("..")) {
            throw new IllegalArgumentException("Invalid skill resource path");
        }
        return path;
    }
}
