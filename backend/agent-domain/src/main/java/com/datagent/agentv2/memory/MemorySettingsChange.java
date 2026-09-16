package com.datagent.agentv2.memory;

/** 能力开关的部分更新；null 表示该字段保持当前值。 */
public record MemorySettingsChange(
        long expectedVersion,
        Boolean readEnabled,
        Boolean explicitWriteEnabled,
        Boolean autoLearningEnabled) {
    public MemorySettingsChange {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion 不得为负数");
        }
    }
}
