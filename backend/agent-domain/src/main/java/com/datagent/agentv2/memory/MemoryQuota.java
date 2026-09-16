package com.datagent.agentv2.memory;

/** 单个分类按 Java UTF-16 单位统计的已用量与上限。 */
public record MemoryQuota(int used, int limit) {
    public MemoryQuota {
        if (used < 0 || limit <= 0 || used > limit) {
            throw new IllegalArgumentException("记忆配额必须满足 0 <= used <= limit");
        }
    }
}
