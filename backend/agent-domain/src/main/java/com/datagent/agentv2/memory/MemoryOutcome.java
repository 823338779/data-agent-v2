package com.datagent.agentv2.memory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/** 统一写入结果；targetExists 区分历史成功重放与目标当前是否仍存在。 */
public record MemoryOutcome(
        MemoryStatus status,
        String memoryId,
        Long version,
        long revision,
        boolean created,
        boolean targetExists,
        Map<MemoryKind, MemoryQuota> quotas) {
    private static final EnumSet<MemoryStatus> SUCCESSFUL =
            EnumSet.of(
                    MemoryStatus.CREATED,
                    MemoryStatus.ALREADY_EXISTS,
                    MemoryStatus.UPDATED,
                    MemoryStatus.REMOVED,
                    MemoryStatus.CONFIRMED);

    public MemoryOutcome {
        Objects.requireNonNull(status, "status");
        quotas = Map.copyOf(quotas);
        if (revision < 0 || (version != null && version < 1)) {
            throw new IllegalArgumentException("结果版本不得越界");
        }
    }

    public boolean successful() {
        return SUCCESSFUL.contains(status);
    }

    /** 从已校验的目标与库存组装结果；目标为空时没有可返回的条目版本。 */
    public static MemoryOutcome composeFromEntry(
            MemoryStatus status,
            MemoryEntry entry,
            long revision,
            Map<MemoryKind, MemoryQuota> quotas) {
        return new MemoryOutcome(
                status,
                entry == null ? null : entry.memoryId(),
                entry == null ? null : entry.version(),
                revision,
                status == MemoryStatus.CREATED,
                entry != null,
                quotas);
    }

    /** 历史结果的其余字段保持不变，避免重放伪装成新的写入或重新恢复正文。 */
    public MemoryOutcome withTargetExists(boolean exists) {
        return new MemoryOutcome(status, memoryId, version, revision, created, exists, quotas);
    }
}
