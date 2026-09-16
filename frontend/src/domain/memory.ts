/** 个人记忆只有两个稳定的 wire 分类，前端不得发送显示名称或历史别名。 */
export type MemoryKind = 'person_profile' | 'collaboration_preference';

export type MemorySource = 'USER_UI' | 'USER_DIRECT' | 'LLM_ACTIVE' | 'BACKGROUND_REVIEW';

export interface MemoryEntry {
  memoryId: string;
  kind: MemoryKind;
  content: string;
  source: MemorySource;
  userProtected: boolean;
  /** 条目级乐观锁版本，与设置版本相互独立。 */
  version: number;
  /** 服务端已按业务时区返回带 offset 的 ISO 文本。 */
  updatedAt: string;
}

export interface MemoryQuota {
  /** 当前分类完整库存的 UTF-16 正文用量，不是当前已加载页的合计。 */
  used: number;
  limit: number;
}

export interface MemoryGroup {
  entries: MemoryEntry[];
  nextCursor: string | null;
}

export interface MemoryListView {
  groups: Record<MemoryKind, MemoryGroup>;
  quotas: Record<MemoryKind, MemoryQuota>;
  revision: number;
}

export interface CreateMemoryRequest {
  kind: MemoryKind;
  content: string;
  restore?: boolean;
}

export interface ReplaceMemoryRequest {
  content: string;
  expectedVersion: number;
}

export type MemoryOutcomeStatus =
  'CREATED' | 'ALREADY_EXISTS' | 'UPDATED' | 'REMOVED' | 'CONFIRMED';

export interface MemoryOutcome {
  status: MemoryOutcomeStatus;
  memoryId: string;
  version: number;
  revision: number;
  created: boolean;
  targetExists: boolean;
  quotas: Record<MemoryKind, MemoryQuota>;
}

export interface MemorySettings {
  /** 设置自己的 CAS 版本，不能使用任一条目的 version 代替。 */
  settingsVersion: number;
  readEnabled: boolean;
  explicitWriteEnabled: boolean;
  autoLearningEnabled: boolean;
}

export interface UpdateMemorySettingsRequest {
  expectedVersion: number;
  readEnabled?: boolean;
  explicitWriteEnabled?: boolean;
  autoLearningEnabled?: boolean;
}
