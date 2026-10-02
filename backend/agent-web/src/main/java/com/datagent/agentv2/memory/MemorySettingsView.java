package com.datagent.agentv2.memory;

import java.util.Objects;

/** 管理界面可读写的个人记忆能力设置；settingsVersion 独立于条目 version。 */
public record MemorySettingsView(
        /** 设置 CAS 版本，从 0 开始，与条目版本和学习代次互相独立。 */
        long settingsVersion,
        /** 是否允许在模型上下文及工具中读取记忆；管理界面仍可查看。 */
        boolean readEnabled,
        /** 是否允许用户显式请求写入，关闭不妨碍修改设置。 */
        boolean explicitWriteEnabled,
        /** 是否允许后台根据已完成的会话自动学习。 */
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
