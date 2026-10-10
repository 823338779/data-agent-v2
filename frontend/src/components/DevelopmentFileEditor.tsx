import { useEffect, useRef, useState } from 'react';
import type { DevelopmentClient } from '../api/developmentClient';

const LABELS = {
  source: '文件源码',
  write: '写入本地文件',
  saved: '已写入本地临时文件，尚未保存到 Git。',
  dirty: '源码尚未写入本地文件。',
  error: '文件操作失败，编辑内容已保留。',
  path: '新文件路径',
  create: '创建本地文件',
  discard: '放弃未写入修改',
  diff: '查看本地差异',
  warning: '本地临时文件不保存在数据库；需要“保存到 Git”才能保留到远端。',
};
const UPDATE = 'UPDATE';
const CREATE = 'CREATE';
/** 源码模式直接编辑完整正文，避免未知 YAML 字段、锚点或注释被表单序列化丢弃。 */
export function DevelopmentFileEditor({
  sessionId,
  client,
  onChanged,
  onDirtyChange,
}: {
  sessionId: string;
  client: DevelopmentClient;
  onChanged: () => void;
  /** 通知上层禁用尚未包含浏览器输入的 Git 动作。 */
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [files, setFiles] = useState<string[]>([]);
  const [revision, setRevision] = useState(0);
  const [file, setFile] = useState<{ path: string; content: string; sha256: string } | null>(null);
  const [content, setContent] = useState('');
  const [newPath, setNewPath] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [diff, setDiff] = useState<string | null>(null);
  const locked = useRef(false);
  const dirty = file !== null && content !== file.content;
  useEffect(() => onDirtyChange?.(dirty), [dirty, onDirtyChange]);
  useEffect(() => {
    const abort = new AbortController();
    void client
      .files(sessionId, abort.signal)
      .then((value) => {
        if (!abort.signal.aborted) {
          setFiles(value.files);
          setRevision(value.revision);
        }
      })
      .catch((reason) => {
        if (!abort.signal.aborted)
          setError(reason instanceof Error ? reason.message : LABELS.error);
      });
    return () => abort.abort();
  }, [client, sessionId]);
  async function run(action: () => Promise<void>) {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await action();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : LABELS.error);
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  async function open(path: string) {
    const current = await client.file(sessionId, path, new AbortController().signal);
    setFile(current);
    setContent(current.content);
    setDiff(null);
  }
  async function write(create: boolean) {
    const path = create ? newPath : file!.path;
    const value = await client.writeFiles(
      sessionId,
      {
        expectedRevision: revision,
        operations: [
          create
            ? { type: CREATE, path, content }
            : { type: UPDATE, path, content, expectedSha256: file!.sha256 },
        ],
      },
      new AbortController().signal,
    );
    setRevision(value.revision);
    onChanged();
    // 重新读取服务端摘要，不在浏览器推测文件编码或摘要算法。
    await open(path);
    setMessage(LABELS.saved);
    if (create) {
      setFiles((current) => [...current, path]);
      setNewPath('');
    }
  }
  return (
    <section className="development-editor" aria-label="工程源码编辑">
      <p>{LABELS.warning}</p>
      <ul>
        {files.map((path) => (
          <li key={path}>
            <button
              type="button"
              disabled={busy || dirty}
              onClick={() => void run(() => open(path))}
            >
              {path}
            </button>
          </li>
        ))}
      </ul>
      <label>
        {LABELS.path}
        <input
          value={newPath}
          onChange={(event) => setNewPath(event.target.value)}
          disabled={busy || dirty}
        />
      </label>
      {(file || newPath) && (
        <>
          <label>
            {LABELS.source}
            <textarea
              rows={14}
              value={content}
              onChange={(event) => setContent(event.target.value)}
              disabled={busy}
            />
          </label>
          {file && (
            <button
              type="button"
              disabled={busy || !dirty}
              onClick={() => void run(() => write(false))}
            >
              {LABELS.write}
            </button>
          )}
          {newPath && (
            <button
              type="button"
              disabled={busy || dirty}
              onClick={() => void run(() => write(true))}
            >
              {LABELS.create}
            </button>
          )}
          {dirty && (
            <>
              <p>{LABELS.dirty}</p>
              <button type="button" disabled={busy} onClick={() => setContent(file!.content)}>
                {LABELS.discard}
              </button>
            </>
          )}
        </>
      )}
      <button
        type="button"
        disabled={busy}
        onClick={() =>
          void run(async () =>
            setDiff((await client.diff(sessionId, new AbortController().signal)).diff),
          )
        }
      >
        {LABELS.diff}
      </button>
      {diff !== null && <pre>{diff}</pre>}
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
