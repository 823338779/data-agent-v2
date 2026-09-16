package com.datagent.agentv2.memory;

/** 单个分类按 Java UTF-16 单位统计的实际已用量与当前配置上限；下调配置后的存量允许暂时超额，写入限制由统一服务检查。 */
public record MemoryQuota(int used, int limit) {
    public MemoryQuota {
        if (used < 0 || limit <= 0) {
            throw new IllegalArgumentException("记忆配额必须满足 used >= 0 且 limit > 0");
        }
    }
}
