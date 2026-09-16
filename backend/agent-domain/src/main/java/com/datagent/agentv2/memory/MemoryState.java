package com.datagent.agentv2.memory;

import java.time.LocalDateTime;

/**
 * 固定身份的记忆版本与能力开关快照。
 *
 * @param revision 任一正文、保护状态、确认时间或能力设置变化后递增的整体快照版本
 * @param settingsVersion 仅能力设置发生变化时递增的设置版本，用于设置更新 CAS
 * @param readEnabled 是否允许向模型上下文注入已保存记忆
 * @param explicitWriteEnabled 是否允许人工管理及用户明确要求的写入
 * @param autoLearningEnabled 是否同时允许主 Agent 推断和后台审查写入
 * @param learningEpoch 学习开关切换时推进的代次，用于令旧后台任务失效；初始值为 1
 * @param learningEnabledAt 当前学习代次启用时间；按数据库审计约定使用无时区 {@link LocalDateTime}，从未启用或当前关闭时可为空
 */
public record MemoryState(
        long revision,
        long settingsVersion,
        boolean readEnabled,
        boolean explicitWriteEnabled,
        boolean autoLearningEnabled,
        long learningEpoch,
        LocalDateTime learningEnabledAt) {
    /** 空字段保持原设置；仅实际切换推进版本，学习开关独立推进代次和启用边界。 */
    public MemoryState changed(MemorySettingsChange change, LocalDateTime now) {
        boolean read = change.readEnabled() == null ? readEnabled : change.readEnabled();
        boolean explicit =
                change.explicitWriteEnabled() == null
                        ? explicitWriteEnabled
                        : change.explicitWriteEnabled();
        boolean auto =
                change.autoLearningEnabled() == null
                        ? autoLearningEnabled
                        : change.autoLearningEnabled();
        if (read == readEnabled && explicit == explicitWriteEnabled && auto == autoLearningEnabled)
            return this;
        boolean learningChanged = auto != autoLearningEnabled;
        return new MemoryState(
                revision + 1,
                settingsVersion + 1,
                read,
                explicit,
                auto,
                learningEpoch + (learningChanged ? 1 : 0),
                learningChanged ? (auto ? now : null) : learningEnabledAt);
    }

    public MemoryState {
        if (revision < 0 || settingsVersion < 0 || learningEpoch < 1) {
            throw new IllegalArgumentException("记忆状态版本不得越界");
        }
    }
}
