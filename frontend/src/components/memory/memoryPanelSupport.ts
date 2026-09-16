import { MemoryClient } from '../../api/memoryClient';
import { MemoryEntry, MemoryGroup, MemoryKind, MemoryQuota } from '../../domain/memory';

export interface MemoryKindRefresh {
  group: MemoryGroup;
  quotas: Record<MemoryKind, MemoryQuota>;
  revision: number;
  target: MemoryEntry | null;
  /** 只有游标走到末尾仍找不到目标，才可确认条目已被删除。 */
  targetMissing: boolean;
}

/**
 * 冲突恢复从分类首屏重新读取，并沿服务端游标找到目标。
 * 找到目标后仍补足用户此前已加载的条数，避免刷新把第二页内容静默丢掉。
 */
export async function reloadMemoryKindThroughTarget(
  client: MemoryClient,
  kind: MemoryKind,
  targetId: string,
  loadedCount: number,
): Promise<MemoryKindRefresh> {
  const entries: MemoryEntry[] = [];
  const seenIds = new Set<string>();
  const seenCursors = new Set<string>();
  let cursor: string | undefined;
  let nextCursor: string | null = null;
  let latestQuotas: Record<MemoryKind, MemoryQuota> | null = null;
  let latestRevision = 0;
  let target: MemoryEntry | null = null;

  do {
    const page = await client.list(kind, cursor);
    const group = page.groups[kind];
    for (const entry of group.entries) {
      if (!seenIds.has(entry.memoryId)) {
        seenIds.add(entry.memoryId);
        entries.push(entry);
      }
      if (entry.memoryId === targetId) target = entry;
    }
    latestQuotas = page.quotas;
    latestRevision = page.revision;
    nextCursor = group.nextCursor;

    if ((target && entries.length >= loadedCount) || nextCursor === null) break;
    // 重复游标意味着服务端分页没有前进，不能据此误判目标已删除。
    if (seenCursors.has(nextCursor)) throw new Error('MEMORY_CURSOR_DID_NOT_ADVANCE');
    seenCursors.add(nextCursor);
    cursor = nextCursor;
  } while (true);

  if (!latestQuotas) throw new Error('MEMORY_REFRESH_EMPTY');
  return {
    group: { entries, nextCursor },
    quotas: latestQuotas,
    revision: latestRevision,
    target,
    targetMissing: target === null && nextCursor === null,
  };
}
