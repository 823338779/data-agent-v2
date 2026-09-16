package com.datagent.agentv2.memory;

import java.time.LocalDateTime;

/** 固定身份的记忆版本与能力开关快照。 */
public record MemoryState(
        long revision,
        long settingsVersion,
        boolean readEnabled,
        boolean explicitWriteEnabled,
        boolean autoLearningEnabled,
        long learningEpoch,
        LocalDateTime learningEnabledAt) {
    public MemoryState {
        if (revision < 0 || settingsVersion < 0 || learningEpoch < 1) {
            throw new IllegalArgumentException("记忆状态版本不得越界");
        }
    }
}
