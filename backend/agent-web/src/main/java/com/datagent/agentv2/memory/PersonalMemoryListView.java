package com.datagent.agentv2.memory;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 同一记忆快照派生的分类分页结果；两个分类始终存在，游标互不借用。 */
public record PersonalMemoryListView(
        Map<MemoryKind, Group> groups, Map<MemoryKind, MemoryQuota> quotas, long revision) {

    private static final int DEFAULT_LIMIT = 20;

    public PersonalMemoryListView {
        groups = Map.copyOf(groups);
        quotas = Map.copyOf(quotas);
    }

    /** 使用接口默认页大小组装完整的首屏分类结果。 */
    public static PersonalMemoryListView composeFromSnapshot(MemorySnapshot snapshot) {
        return composeFromSnapshot(snapshot, null, null, DEFAULT_LIMIT);
    }

    /**
     * 从一个一致快照按稳定 memoryId 游标分页。
     *
     * <p>未请求的分类保留空组；配额始终来自完整快照，不能用当前页正文重新计算。
     */
    public static PersonalMemoryListView composeFromSnapshot(
            MemorySnapshot snapshot, MemoryKind requestedKind, String cursor, int limit) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        var groups = new EnumMap<MemoryKind, Group>(MemoryKind.class);
        for (MemoryKind kind : MemoryKind.values()) {
            groups.put(
                    kind,
                    requestedKind == null || requestedKind == kind
                            ? page(snapshot.entries(), kind, cursor, limit)
                            : Group.empty());
        }
        return new PersonalMemoryListView(groups, snapshot.quotas(), snapshot.state().revision());
    }

    private static Group page(
            List<MemoryEntry> entries, MemoryKind kind, String cursor, int limit) {
        List<MemoryEntry> candidates =
                entries.stream()
                        .filter(memory -> memory.kind() == kind)
                        .filter(memory -> cursor == null || memory.memoryId().compareTo(cursor) > 0)
                        .sorted(Comparator.comparing(MemoryEntry::memoryId))
                        .toList();
        List<PersonalMemoryView> page =
                candidates.stream()
                        .limit(limit)
                        .map(PersonalMemoryView::composeFromMemory)
                        .toList();
        String nextCursor =
                candidates.size() > page.size() && !page.isEmpty()
                        ? page.getLast().memoryId()
                        : null;
        return new Group(page, nextCursor);
    }

    /** 单个分类的当前页；nextCursor 仅在该分类还有后续条目时存在。 */
    public record Group(List<PersonalMemoryView> entries, String nextCursor) {
        public Group {
            entries = List.copyOf(entries);
        }

        private static Group empty() {
            return new Group(List.of(), null);
        }
    }
}
