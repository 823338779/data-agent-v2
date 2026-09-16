package com.datagent.agentv2.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 同一 revision 下读取的状态、条目和配额不可变快照。 */
public record MemorySnapshot(
        MemoryState state, List<MemoryEntry> entries, Map<MemoryKind, MemoryQuota> quotas) {
    public MemorySnapshot {
        Objects.requireNonNull(state, "state");
        entries = List.copyOf(entries);
        quotas = Map.copyOf(quotas);
    }
}
