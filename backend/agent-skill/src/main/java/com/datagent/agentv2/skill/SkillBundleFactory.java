package com.datagent.agentv2.skill;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 校验 bundled Skill 代码定义与资源，并组装不可变运行时资源包。 */
@Component
public final class SkillBundleFactory {
    private static final int MAX_RESOURCE_BYTES = 256 * 1024;

    public SkillBundle create(SkillDefinition definition, Map<String, byte[]> resources) {
        String logicalId = definition.logicalId();
        String description = definition.description();
        List<String> requiredTools = definition.requiredTools();
        if (logicalId == null || !logicalId.matches("[a-z0-9][a-z0-9-]*"))
            throw new IllegalArgumentException("Invalid skill logical id");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("Invalid skill description");
        resources.forEach(
                (path, bytes) -> {
                    SkillBundle.normalize(path);
                    if (bytes.length > MAX_RESOURCE_BYTES)
                        throw new IllegalArgumentException("Skill resource is too large");
                });
        validateUtf8(resources.get("SKILL.md"));
        return new SkillBundle(
                new SkillDescriptor(logicalId, description, requiredTools),
                digest(definition, resources),
                resources);
    }

    private static void validateUtf8(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("SKILL.md is required");
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Skill resource must be UTF-8", failure);
        }
    }

    private static String digest(SkillDefinition definition, Map<String, byte[]> resources) {
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
            // Skill 元数据由代码维护，全部进入摘要后，执行快照才能准确反映指令和权限版本。
            updateField(digest, "logicalId", definition.logicalId());
            updateField(digest, "description", definition.description());
            digest.update("requiredTools".getBytes(StandardCharsets.UTF_8));
            definition.requiredTools().forEach(tool -> updateField(digest, "tool", tool));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateField(MessageDigest digest, String name, String value) {
        digest.update((byte) 0);
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
