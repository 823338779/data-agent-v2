package com.datagent.agentv2.memory;

import java.util.Objects;

/** 管理界面可读写的个人记忆能力设置；settingsVersion 独立于条目 version。 */
public record MemorySettingsView(
        long settingsVersion,
        boolean readEnabled,
        boolean explicitWriteEnabled,
        boolean autoLearningEnabled) {

    /** 从状态快照提取设置接口契约，不暴露后台学习代次和内部时间边界。 */
    public static MemorySettingsView composeFromState(MemoryState state) {
        Objects.requireNonNull(state, "state");
        return new MemorySettingsView(
                state.settingsVersion(),
                state.readEnabled(),
                state.explicitWriteEnabled(),
                state.autoLearningEnabled());
    }
}
