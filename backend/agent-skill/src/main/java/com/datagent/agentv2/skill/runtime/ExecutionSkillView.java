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
        if (snapshots.isEmpty()) return "";
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
    public ReadResult read(String logicalId, String path) {
        Snapshot snapshot = snapshots.get(logicalId);
        if (snapshot == null) throw new IllegalArgumentException("Unknown skill: " + logicalId);
        SkillBundle current = catalog.require(logicalId);
        if (!snapshot.digest().equals(current.digest()))
            throw new IllegalStateException("Skill digest changed during execution");
        return new ReadResult(
                snapshot.descriptor(), snapshot.digest(), path, current.readText(path));
    }

    private record Snapshot(SkillDescriptor descriptor, String digest) {}

    public record ReadResult(
            SkillDescriptor descriptor, String digest, String path, String content) {}
}
