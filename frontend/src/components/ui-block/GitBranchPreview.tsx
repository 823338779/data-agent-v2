import { useEffect, useState } from 'react';
import { createGitWorkspaceClient, type BranchRelease } from '../../api/gitWorkspaceClient';

const client = createGitWorkspaceClient();
const ACTIVE = 'ACTIVE';
const PUBLISHED = 'PUBLISHED';
const FAILED = 'FAILED';
const SUPERSEDED = 'SUPERSEDED';
const POLL_INTERVAL = 5000;
const POLL_WINDOW = 30000;
const LOADING = '正在读取预览状态';
const WAITING = '等待此提交的预览构建';
const UNAVAILABLE = '预览状态暂不可用，可刷新重试';

/** 链接基址仅来自部署配置，身份来自候选及服务返回的固定版本。 */
function previewLink(projectId: string, branchId: string, releaseId: string): string | null {
  const base = import.meta.env.VITE_METRIC_PLATFORM_URL;
  if (!base) return null;
  try {
    const target = new URL(base);
    if (!['http:', 'https:'].includes(target.protocol)) return null;
    target.pathname = `${target.pathname.replace(/\/$/, '')}/dbt/projects/${encodeURIComponent(projectId)}/catalog`;
    target.search = new URLSearchParams({ branchId, releaseId }).toString();
    target.hash = '';
    return target.toString();
  } catch {
    return null;
  }
}

/** 始终查找卡片原提交，后续分支提交不会替换这里展示的构建版本。 */
export function GitBranchPreview({
  branchId,
  projectId,
  commitId,
}: {
  branchId: string;
  projectId: string;
  commitId: string;
}) {
  const [release, setRelease] = useState<BranchRelease | null>(null);
  const [message, setMessage] = useState(LOADING);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    const deadline = Date.now() + POLL_WINDOW;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setRelease(null);
    setMessage(LOADING);
    async function read() {
      try {
        const branch = await client.branch(branchId, abort.signal);
        if (abort.signal.aborted) return;
        if (branch.status !== ACTIVE) {
          setMessage('原分支已删除或不可用');
          return;
        }
        const releases = await client.releases(branchId, abort.signal);
        const matching = releases
          .filter((item) => item.sourceSha === commitId)
          .sort((left, right) => right.publicationSequence - left.publicationSequence)[0];
        const current = matching
          ? await client.release(branchId, matching.releaseId, abort.signal)
          : null;
        if (abort.signal.aborted) return;
        setRelease(current);
        setMessage(
          !current
            ? WAITING
            : current.state === PUBLISHED
              ? '此提交预览已发布'
              : current.state === FAILED
                ? '预览构建失败'
                : current.state === SUPERSEDED
                  ? '此预览构建已被后续版本替代'
                  : '此提交预览正在构建',
        );
        if (
          (!current || ![PUBLISHED, FAILED, SUPERSEDED].includes(current.state)) &&
          Date.now() < deadline
        )
          timer = setTimeout(() => void read(), POLL_INTERVAL);
      } catch {
        if (!abort.signal.aborted) setMessage(UNAVAILABLE);
      }
    }
    void read();
    return () => {
      abort.abort();
      if (timer) clearTimeout(timer);
    };
  }, [branchId, projectId, commitId, refresh]);
  const link = release ? previewLink(projectId, branchId, release.releaseId) : null;
  return (
    <section aria-label="分支预览" className="git-branch-preview">
      <strong>{message}</strong>
      <p>Git 推送已完成；预览构建状态单独展示。</p>
      {release?.validationSummary && (
        <ul>
          {release.validationSummary.checks.map((check) => (
            <li key={check.name}>
              {check.name}：{check.status}
            </li>
          ))}
        </ul>
      )}
      {release?.validationSummary?.truncated && <p>验证摘要已截断，请到指标平台查看。</p>}
      {link && (
        <a href={link} target="_blank" rel="noopener noreferrer">
          在指标平台查看此版本
        </a>
      )}
      <button type="button" onClick={() => setRefresh((value) => value + 1)}>
        刷新预览状态
      </button>
    </section>
  );
}
