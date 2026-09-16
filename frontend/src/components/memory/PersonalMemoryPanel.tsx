import { FormEvent, useEffect, useRef, useState } from 'react';
import { createIdempotencyKey } from '../../api/idempotencyKey';
import { MemoryApiError, MemoryClient } from '../../api/memoryClient';
import {
  MemoryEntry,
  MemoryKind,
  MemoryListView,
  MemoryOutcome,
  MemorySettings,
} from '../../domain/memory';

interface PersonalMemoryPanelProps {
  client: MemoryClient;
  onClose(): void;
}

interface OperationKey {
  signature: string;
  key: string;
}

interface EditState {
  memoryId: string;
  kind: MemoryKind;
  draft: string;
  serverVersion: number;
  reconfirmRequired: boolean;
}

const KINDS: MemoryKind[] = ['person_profile', 'collaboration_preference'];
const KIND_LABELS: Record<MemoryKind, string> = {
  person_profile: '个人画像',
  collaboration_preference: '协作偏好',
};
const EMPTY_COPY: Record<MemoryKind, string> = {
  person_profile: '还没有个人画像记忆。',
  collaboration_preference: '还没有协作偏好记忆。',
};
const SOURCE_LABELS: Record<MemoryEntry['source'], string> = {
  USER_UI: '页面保存',
  USER_DIRECT: '明确请求',
  LLM_ACTIVE: '对话学习',
  BACKGROUND_REVIEW: '后台学习',
};
const DEFINITIVE_REJECTION_STATUSES = new Set([400, 404, 409, 422]);
const DEFINITIVE_REJECTION_CODES = new Set([
  'INVALID_REQUEST',
  'UNSAFE_CONTENT',
  'CONFLICT',
  'DUPLICATE_CONFLICT',
  'NOT_FOUND',
  'PROTECTED',
  'SUPPRESSED',
  'QUOTA_EXCEEDED',
  'LEARNING_DISABLED',
  'LEASE_LOST',
]);

function operationKey(slot: React.MutableRefObject<OperationKey | null>, signature: string) {
  if (slot.current?.signature === signature) return slot.current.key;
  const key = createIdempotencyKey();
  slot.current = { signature, key };
  return key;
}

/** 只有契约中明确识别的业务拒绝会结束旧操作；超时、限流和未知 5xx 的提交结果仍不确定。 */
function isDefinitiveRejection(failure: unknown): failure is MemoryApiError {
  return (
    failure instanceof MemoryApiError &&
    DEFINITIVE_REJECTION_STATUSES.has(failure.status) &&
    DEFINITIVE_REJECTION_CODES.has(failure.code)
  );
}

/**
 * 独立管理个人记忆的临时面板。它只持有自己的分页、草稿和乐观锁状态，
 * 不触碰工作区路由及会话流生命周期。
 */
export function PersonalMemoryPanel({ client, onClose }: PersonalMemoryPanelProps) {
  const [view, setView] = useState<MemoryListView | null>(null);
  const [settings, setSettings] = useState<MemorySettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [loadingMore, setLoadingMore] = useState<MemoryKind | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [kind, setKind] = useState<MemoryKind>('person_profile');
  const [content, setContent] = useState('');
  const [restorePending, setRestorePending] = useState(false);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [deleteConfirmation, setDeleteConfirmation] = useState<string | null>(null);
  const createKey = useRef<OperationKey | null>(null);
  const replaceKey = useRef<OperationKey | null>(null);
  const deleteKey = useRef<OperationKey | null>(null);
  const settingsKey = useRef<OperationKey | null>(null);

  useEffect(() => {
    let cancelled = false;
    void Promise.all([client.list(), client.settings()])
      .then(([loadedView, loadedSettings]) => {
        if (cancelled) return;
        setView(loadedView);
        setSettings(loadedSettings);
      })
      .catch(() => {
        if (!cancelled) setFeedback('个人记忆加载失败，请关闭后重试。');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  async function refreshAll() {
    const refreshed = await client.list();
    setView(refreshed);
    return refreshed;
  }

  /** 分类刷新只替换该分类；kind 查询返回的另一个空组不能清掉已加载数据。 */
  async function refreshKind(targetKind: MemoryKind) {
    const refreshed = await client.list(targetKind);
    setView((current) => {
      if (!current) return refreshed;
      return {
        groups: { ...current.groups, [targetKind]: refreshed.groups[targetKind] },
        quotas: refreshed.quotas,
        revision: refreshed.revision,
      };
    });
    return refreshed.groups[targetKind].entries;
  }

  function outcomeMessage(result: MemoryOutcome, restored: boolean) {
    if (result.status === 'ALREADY_EXISTS' || !result.created) {
      return '已有相同记忆，已确认现有条目。';
    }
    return restored ? '已重新记住。' : '记忆已保存。';
  }

  async function submitCreate(restore: boolean) {
    const normalized = content.trim();
    if (!normalized || busy) return;
    const request = { kind, content: normalized, restore };
    // restore 是新的业务意图，因此纳入签名，绝不会沿用首次被抑制请求的键。
    const key = operationKey(createKey, JSON.stringify(request));
    setBusy(true);
    setFeedback(null);
    try {
      const result = await client.create(request, key);
      await refreshAll();
      setFeedback(outcomeMessage(result, restore));
      setContent('');
      setRestorePending(false);
      createKey.current = null;
    } catch (failure) {
      if (failure instanceof MemoryApiError && failure.code === 'SUPPRESSED' && !restore) {
        setRestorePending(true);
        setFeedback('这条内容曾被明确遗忘，需要确认后才能重新记住。');
      } else if (failure instanceof MemoryApiError && failure.code === 'QUOTA_EXCEEDED') {
        setFeedback('容量不足，记忆未保存。请删除或精简该分类的内容。');
      } else if (
        failure instanceof MemoryApiError &&
        (failure.code === 'UNSAFE_CONTENT' || failure.code === 'INVALID_REQUEST')
      ) {
        setFeedback('内容不符合保存要求，记忆未保存。');
      } else {
        setFeedback('保存失败，请重试。');
      }
      if (isDefinitiveRejection(failure)) createKey.current = null;
    } finally {
      setBusy(false);
    }
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await submitCreate(false);
  }

  async function loadMore(targetKind: MemoryKind) {
    const cursor = view?.groups[targetKind].nextCursor;
    if (!cursor || loadingMore) return;
    setLoadingMore(targetKind);
    setFeedback(null);
    try {
      const page = await client.list(targetKind, cursor);
      setView((current) => {
        if (!current) return page;
        return {
          groups: {
            ...current.groups,
            [targetKind]: {
              entries: [...current.groups[targetKind].entries, ...page.groups[targetKind].entries],
              nextCursor: page.groups[targetKind].nextCursor,
            },
          },
          // 用量始终以服务端完整库存为准，不能从已加载页正文累加。
          quotas: page.quotas,
          revision: page.revision,
        };
      });
    } catch {
      setFeedback('加载更多记忆失败，请重试。');
    } finally {
      setLoadingMore(null);
    }
  }

  function startEditing(entry: MemoryEntry) {
    setEdit({
      memoryId: entry.memoryId,
      kind: entry.kind,
      draft: entry.content,
      serverVersion: entry.version,
      reconfirmRequired: false,
    });
    replaceKey.current = null;
    setFeedback(null);
  }

  async function saveEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!edit || !edit.draft.trim() || edit.reconfirmRequired || busy) return;
    const request = { content: edit.draft.trim(), expectedVersion: edit.serverVersion };
    const key = operationKey(replaceKey, JSON.stringify({ memoryId: edit.memoryId, ...request }));
    setBusy(true);
    setFeedback(null);
    try {
      await client.replace(edit.memoryId, request, key);
      await refreshAll();
      setEdit(null);
      replaceKey.current = null;
      setFeedback('记忆已更新。');
    } catch (failure) {
      if (failure instanceof MemoryApiError && failure.code === 'CONFLICT') {
        // 冲突只刷新权威条目和版本，draft 保持用户输入，等待显式重新确认。
        const entries = await refreshKind(edit.kind);
        const latest = entries.find((entry) => entry.memoryId === edit.memoryId);
        setEdit((current) =>
          current
            ? {
                ...current,
                serverVersion: latest?.version ?? current.serverVersion,
                reconfirmRequired: true,
              }
            : current,
        );
        replaceKey.current = null;
        setFeedback('版本已变化，已刷新服务器内容。请确认后重新保存。');
      } else {
        if (isDefinitiveRejection(failure)) replaceKey.current = null;
        setFeedback('修改失败，请重试。');
      }
    } finally {
      setBusy(false);
    }
  }

  async function removeEntry(entry: MemoryEntry) {
    if (busy) return;
    const signature = JSON.stringify({ memoryId: entry.memoryId, version: entry.version });
    const key = operationKey(deleteKey, signature);
    setBusy(true);
    setFeedback(null);
    try {
      await client.remove(entry.memoryId, entry.version, key);
      await refreshAll();
      setDeleteConfirmation(null);
      deleteKey.current = null;
      setFeedback('记忆已删除；再次保存需要明确重新记住。');
    } catch (failure) {
      if (failure instanceof MemoryApiError && failure.code === 'CONFLICT') {
        await refreshKind(entry.kind);
        setDeleteConfirmation(null);
        deleteKey.current = null;
        setFeedback('版本已变化，已刷新服务器内容，请重新删除。');
      } else {
        if (isDefinitiveRejection(failure)) deleteKey.current = null;
        setFeedback('删除失败，请重试。');
      }
    } finally {
      setBusy(false);
    }
  }

  async function handleLearningChange(enabled: boolean) {
    if (!settings || busy) return;
    const previous = settings;
    const request = {
      expectedVersion: settings.settingsVersion,
      autoLearningEnabled: enabled,
    };
    const key = operationKey(settingsKey, JSON.stringify(request));
    setSettings({ ...settings, autoLearningEnabled: enabled });
    setBusy(true);
    setFeedback(null);
    try {
      setSettings(await client.updateSettings(request, key));
      settingsKey.current = null;
      setFeedback('自动学习设置已更新。');
    } catch (failure) {
      // 保存失败必须回滚到本次请求前的服务端状态，不能留下虚假的关闭状态。
      if (isDefinitiveRejection(failure)) settingsKey.current = null;
      try {
        setSettings(await client.settings());
      } catch {
        setSettings(previous);
      }
      setFeedback('设置保存失败，请重试。');
    } finally {
      setBusy(false);
    }
  }

  function renderEntry(entry: MemoryEntry) {
    const editing = edit?.memoryId === entry.memoryId;
    return (
      <li className="memory-entry" key={entry.memoryId}>
        {editing ? (
          <form className="memory-edit-form" onSubmit={(event) => void saveEdit(event)}>
            <label>
              <span>编辑记忆内容</span>
              <textarea
                aria-label="编辑记忆内容"
                value={edit.draft}
                onChange={(event) =>
                  setEdit((current) =>
                    current ? { ...current, draft: event.target.value } : current,
                  )
                }
              />
            </label>
            {edit.reconfirmRequired && (
              <button
                type="button"
                onClick={() =>
                  setEdit((current) =>
                    current ? { ...current, reconfirmRequired: false } : current,
                  )
                }
              >
                使用保留内容重新编辑
              </button>
            )}
            <div className="memory-entry-actions">
              <button type="submit" disabled={busy || edit.reconfirmRequired || !edit.draft.trim()}>
                保存修改
              </button>
              <button type="button" onClick={() => setEdit(null)} disabled={busy}>
                取消
              </button>
            </div>
          </form>
        ) : (
          <>
            <p className="memory-entry-content">{entry.content}</p>
            <p className="memory-entry-meta">
              <span>{SOURCE_LABELS[entry.source]}</span>
              <span>{entry.userProtected ? '人工保护' : '可由学习更新'}</span>
              <time dateTime={entry.updatedAt}>{entry.updatedAt}</time>
            </p>
            <div className="memory-entry-actions">
              <button type="button" onClick={() => startEditing(entry)} disabled={busy}>
                编辑
              </button>
              {deleteConfirmation === entry.memoryId ? (
                <>
                  <button type="button" onClick={() => void removeEntry(entry)} disabled={busy}>
                    确认删除
                  </button>
                  <button type="button" onClick={() => setDeleteConfirmation(null)} disabled={busy}>
                    取消
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  onClick={() => setDeleteConfirmation(entry.memoryId)}
                  disabled={busy}
                >
                  删除
                </button>
              )}
            </div>
          </>
        )}
      </li>
    );
  }

  return (
    <div className="memory-panel-backdrop">
      <section className="memory-panel" role="dialog" aria-modal="true" aria-label="个人记忆">
        <header className="memory-panel-header">
          <div>
            <h1>个人记忆</h1>
            <p>管理跨会话使用的个人信息与协作方式。</p>
          </div>
          <button type="button" aria-label="关闭个人记忆" onClick={onClose}>
            关闭
          </button>
        </header>

        {loading ? (
          <p className="memory-panel-loading">正在读取个人记忆…</p>
        ) : (
          <div className="memory-panel-body">
            {settings && (
              <section className="memory-settings" aria-label="记忆设置">
                <label>
                  <input
                    type="checkbox"
                    aria-label="自动学习"
                    checked={settings.autoLearningEnabled}
                    disabled={busy}
                    onChange={(event) => void handleLearningChange(event.target.checked)}
                  />
                  自动学习
                </label>
                <p>关闭后停止主动提炼和后台学习，仍会使用已有记忆，也可以手动保存。</p>
              </section>
            )}

            <form className="memory-create-form" onSubmit={(event) => void handleCreate(event)}>
              <h2>新增记忆</h2>
              <label>
                分类
                <select
                  value={kind}
                  onChange={(event) => {
                    setKind(event.target.value as MemoryKind);
                    setRestorePending(false);
                  }}
                >
                  <option value="person_profile">个人画像</option>
                  <option value="collaboration_preference">协作偏好</option>
                </select>
              </label>
              <label>
                记忆内容
                <textarea
                  aria-label="记忆内容"
                  value={content}
                  onChange={(event) => {
                    setContent(event.target.value);
                    setRestorePending(false);
                  }}
                  placeholder="写下希望长期记住的内容"
                />
              </label>
              <div className="memory-form-actions">
                <button type="submit" disabled={busy || !content.trim()}>
                  保存记忆
                </button>
                {restorePending && (
                  <button type="button" onClick={() => void submitCreate(true)} disabled={busy}>
                    明确重新记住
                  </button>
                )}
              </div>
            </form>

            {feedback && (
              <p className="memory-feedback" role="status">
                {feedback}
              </p>
            )}

            {view && (
              <div className="memory-groups">
                {KINDS.map((targetKind) => {
                  const group = view.groups[targetKind];
                  const quota = view.quotas[targetKind];
                  return (
                    <section
                      className="memory-group"
                      aria-label={KIND_LABELS[targetKind]}
                      key={targetKind}
                    >
                      <header>
                        <div>
                          <h2>{KIND_LABELS[targetKind]}</h2>
                          <p>
                            已用 {quota.used} / {quota.limit} 字符
                          </p>
                        </div>
                      </header>
                      {group.entries.length === 0 ? (
                        <p className="memory-empty">{EMPTY_COPY[targetKind]}</p>
                      ) : (
                        <ul>{group.entries.map(renderEntry)}</ul>
                      )}
                      {group.nextCursor && (
                        <button
                          className="memory-load-more"
                          type="button"
                          aria-label={`加载更多${KIND_LABELS[targetKind]}`}
                          disabled={loadingMore !== null}
                          onClick={() => void loadMore(targetKind)}
                        >
                          {loadingMore === targetKind ? '正在加载…' : '加载更多'}
                        </button>
                      )}
                    </section>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
