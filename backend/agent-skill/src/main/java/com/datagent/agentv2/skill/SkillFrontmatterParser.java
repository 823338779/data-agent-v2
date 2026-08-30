package com.datagent.agentv2.skill;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 只解析 name、description、required_tools 的受限 frontmatter，避免引入通用 YAML 行为。 */
@Component
public final class SkillFrontmatterParser {
    private static final int MAX_RESOURCE_BYTES = 256 * 1024;

    public SkillBundle parse(String logicalId, Map<String, byte[]> resources) {
        if (!logicalId.matches("[a-z0-9][a-z0-9-]*"))
            throw new IllegalArgumentException("Invalid skill logical id");
        resources.forEach(
                (path, bytes) -> {
                    SkillBundle.normalize(path);
                    if (bytes.length > MAX_RESOURCE_BYTES)
                        throw new IllegalArgumentException("Skill resource is too large");
                });
        String markdown = decode(resources.get("SKILL.md"));
        List<String> lines = markdown.lines().toList();
        if (lines.size() < 3 || !"---".equals(lines.getFirst()))
            throw new IllegalArgumentException("Missing frontmatter delimiter");
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        if (end <= 1) throw new IllegalArgumentException("Missing frontmatter delimiter");
        String name = null, description = null;
        List<String> tools = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        String active = null;
        for (int i = 1; i < end; i++) {
            String line = lines.get(i);
            if (line.startsWith("  - ")) {
                if (!"required_tools".equals(active))
                    throw new IllegalArgumentException("Invalid list entry");
                String tool = line.substring(4).trim();
                if (tool.isEmpty() || tools.contains(tool))
                    throw new IllegalArgumentException("Invalid required tool");
                tools.add(tool);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("Invalid frontmatter line");
            active = line.substring(0, colon);
            if (!Set.of("name", "description", "required_tools").contains(active)
                    || !fields.add(active))
                throw new IllegalArgumentException("Unsupported or duplicate frontmatter field");
            String value = line.substring(colon + 1).trim();
            if ("name".equals(active)) name = value;
            else if ("description".equals(active)) description = value;
            else if (!value.isEmpty())
                throw new IllegalArgumentException("required_tools must be a list");
        }
        if (!logicalId.equals(name) || description == null || description.isBlank())
            throw new IllegalArgumentException("Invalid skill metadata");
        return new SkillBundle(
                new SkillDescriptor(logicalId, description, tools), digest(resources), resources);
    }

    private static String decode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("SKILL.md is required");
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Skill resource must be UTF-8", failure);
        }
    }

    private static String digest(Map<String, byte[]> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            resources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> {
                                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                                digest.update((byte) 0);
                                digest.update(entry.getValue());
                            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
