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
}
