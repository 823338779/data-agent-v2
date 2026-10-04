package com.datagent.agentv2.skill.runtime;

import com.datagent.agentv2.skill.BundledSkillCatalog;
import com.datagent.agentv2.skill.SkillBundle;
import com.datagent.agentv2.skill.SkillDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;

/** 单次执行冻结的 Skill 摘要与 digest 视图。 */
public final class ExecutionSkillView {
    private final BundledSkillCatalog catalog;
    private final Map<String, Snapshot> snapshots;

    /** 本次执行已经成功读取的冻结正文，防止摘要后丢失必要指令。 */
    private final Map<ReadKey, ReadResult> readResources = new LinkedHashMap<>();

    ExecutionSkillView(BundledSkillCatalog catalog) {
        this.catalog = catalog;
        Map<String, Snapshot> frozen = new LinkedHashMap<>();
        for (SkillBundle bundle : catalog.bundlesInOrder()) {
            SkillDescriptor descriptor = bundle.descriptor();
            frozen.put(descriptor.logicalId(), new Snapshot(descriptor, bundle.digest()));
        }
        this.snapshots = Map.copyOf(frozen);
    }

    /** 只向模型披露摘要，完整指令必须通过 skill_read 获取。 */
    public String systemPrompt() {
        if (snapshots.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder("可用技能（仅摘要）：\n");
        snapshots
                .values()
                .forEach(
                        snapshot ->
                                prompt.append("- ")
                                        .append(snapshot.descriptor().logicalId())
                                        .append("：")
                                        .append(snapshot.descriptor().description())
                                        .append('\n'));
        return prompt.append("需要使用某项技能时，先调用 skill_read，传入 logical_id；不要根据摘要猜测完整指令。").toString();
    }

    /** 校验目录快照未漂移后读取内存资源。 */
    public synchronized ReadResult read(String logicalId, String path) {
        Snapshot snapshot = snapshots.get(logicalId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown skill: " + logicalId);
        }
        SkillBundle current = catalog.require(logicalId);
        if (!snapshot.digest().equals(current.digest())) {
            throw new IllegalStateException("Skill digest changed during execution");
        }
        var key = new ReadKey(logicalId, snapshot.digest(), path);
        return readResources.computeIfAbsent(
                key,
                ignored ->
                        new ReadResult(
                                snapshot.descriptor(),
                                snapshot.digest(),
                                path,
                                current.readText(path)));
    }

    /** 只返回成功读取过的资源，保持原始来源身份与读取顺序。 */
    public synchronized java.util.List<ReadResult> readConstraints() {
        return java.util.List.copyOf(readResources.values());
    }

    private record ReadKey(String logicalId, String digest, String path) {}

    private record Snapshot(SkillDescriptor descriptor, String digest) {}

    public record ReadResult(
            SkillDescriptor descriptor, String digest, String path, String content) {}
}
