import { FormEvent, useEffect, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  Folder,
  MessageSquarePlus,
  MoreHorizontal,
  Plus,
  Search,
} from 'lucide-react';
import { ConversationSession } from '../domain/agent';
import {
  ConversationProject,
  ConversationSidebarSnapshot,
  SetConversationOrganization,
} from '../domain/sidebar';
import { groupSidebar } from '../app/conversationSidebarModel';
import { SidebarMutationResult } from '../app/useConversationSidebar';
import { ConversationList } from './ConversationList';

interface ConversationSidebarProps {
  snapshot: ConversationSidebarSnapshot;
  activeSessionId?: string;
  loading: boolean;
  busy: boolean;
  error: string | null;
  storageKey: string;
  onOpen: (id: string) => void;
  onRename: (id: string, title: string) => Promise<boolean>;
  onCreateSession: (projectId: string | null) => Promise<SidebarMutationResult>;
  onCreateProject: (name: string) => Promise<SidebarMutationResult>;
  onRenameProject: (id: string, name: string, version: number) => Promise<SidebarMutationResult>;
  onDeleteProject: (id: string, version: number) => Promise<SidebarMutationResult>;
  onSetOrganization: (
    id: string,
    request: SetConversationOrganization,
  ) => Promise<SidebarMutationResult>;
  onRefresh: () => unknown;
}

function readCollapsed(key: string): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? '[]');
    return Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

/** 只消费侧栏快照；项目是一级整理容器，不参与对话内容或执行状态的生命周期。 */
export function ConversationSidebar(props: ConversationSidebarProps) {
  const { snapshot, loading, busy, error, storageKey } = props;
  const [query, setQuery] = useState('');
  const [collapsed, setCollapsed] = useState<string[]>(() => readCollapsed(storageKey));
  const [menuId, setMenuId] = useState<string | null>(null);
  const [editor, setEditor] = useState<ConversationProject | 'new' | null>(null);
  const [name, setName] = useState('');
  const [deleting, setDeleting] = useState<ConversationProject | null>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const cancelDelete = useRef<HTMLButtonElement>(null);
  const groups = groupSidebar(snapshot, query);
  const disabled = loading || busy;
  const noMatches =
    query.trim() && !groups.pinned.length && !groups.ungrouped.length && !groups.projects.length;

  useEffect(() => {
    setCollapsed(readCollapsed(storageKey));
  }, [storageKey]);
  useEffect(() => {
    if (loading || error) return;
    setCollapsed((current) => {
      const next = current.filter((id) =>
        snapshot.projects.some((project) => project.projectId === id),
      );
      if (next.length === current.length) return current;
      try {
        localStorage.setItem(storageKey, JSON.stringify(next));
      } catch {
        /* 本地记录清理失败不阻塞侧栏。 */
      }
      return next;
    });
  }, [snapshot.projects, loading, error, storageKey]);
  useEffect(() => {
    if (editor) {
      input.current?.focus();
      input.current?.select();
    }
  }, [editor]);
  useEffect(() => {
    if (deleting) cancelDelete.current?.focus();
  }, [deleting]);

  function remember(next: string[]) {
    setCollapsed(next);
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
    } catch {
      /* 存储不可用时保留页面内折叠状态。 */
    }
  }
  function closeAction() {
    setEditor(null);
    setDeleting(null);
    setMenuId(null);
    trigger.current?.focus();
  }
  async function saveProject(event: FormEvent) {
    event.preventDefault();
    if (!editor || disabled || !name.trim()) return;
    const result =
      editor === 'new'
        ? await props.onCreateProject(name.trim())
        : await props.onRenameProject(
            editor.projectId,
            name.trim(),
            snapshot.projects.find((project) => project.projectId === editor.projectId)?.version ??
              editor.version,
          );
    // 写入成功后即使刷新失败也关闭编辑，避免再次提交已经创建的项目。
    if (result.outcome === 'saved') closeAction();
  }
  async function removeProject() {
    if (!deleting || disabled) return;
    const result = await props.onDeleteProject(
      deleting.projectId,
      snapshot.projects.find((project) => project.projectId === deleting.projectId)?.version ??
        deleting.version,
    );
    if (result.outcome === 'saved') {
      remember(collapsed.filter((id) => id !== deleting.projectId));
      closeAction();
    }
  }
  async function organize(id: string, request: SetConversationOrganization) {
    const result = await props.onSetOrganization(id, request);
    if (result.outcome === 'saved' && id === props.activeSessionId && request.projectId) {
      remember(collapsed.filter((projectId) => projectId !== request.projectId));
    }
    return result;
  }
  function list(sessions: ConversationSession[], showOrigin = false) {
    return (
      <ConversationList
        sessions={sessions}
        activeSessionId={props.activeSessionId}
        onOpen={props.onOpen}
        onRename={props.onRename}
        projects={snapshot.projects}
        busy={disabled}
        showOrigin={showOrigin}
        onSetOrganization={organize}
      />
    );
  }
  function projectEditor() {
    return (
      <form
        className="sidebar-project-editor"
        onSubmit={(event) => void saveProject(event)}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) {
            if (event.key === 'Enter') event.preventDefault();
            return;
          }
          if (event.key === 'Escape' && !busy) {
            event.preventDefault();
            closeAction();
          }
        }}
      >
        <input
          ref={input}
          aria-label="项目名称"
          value={name}
          maxLength={60}
          disabled={disabled}
          onChange={(event) => setName(event.target.value)}
          placeholder="项目名称"
        />
        <div>
          <button type="submit" disabled={disabled || !name.trim()}>
            保存项目
          </button>
          <button type="button" disabled={busy} onClick={closeAction}>
            取消
          </button>
        </div>
      </form>
    );
  }

  return (
    <div
      className="conversation-sidebar-content"
      onKeyDown={(event) => {
        if (event.key === 'Escape' && !event.nativeEvent.isComposing && !busy) closeAction();
      }}
    >
      <button
        className="sidebar-new-session"
        type="button"
        disabled={disabled}
        onClick={() => void props.onCreateSession(null)}
      >
        <MessageSquarePlus size={17} />
        新会话
      </button>
      <label className="session-search">
        <Search size={15} />
        <span className="sr-only">搜索会话</span>
        <input
          placeholder="搜索会话"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>
      {error && (
        <div className="sidebar-feedback" role="alert">
          {error}
          <button type="button" disabled={busy} onClick={() => props.onRefresh()}>
            重试刷新
          </button>
        </div>
      )}
      {loading && (
        <p className="sidebar-empty" role="status">
          正在读取分组…
        </p>
      )}
      {!loading && !(error && !snapshot.projects.length && !snapshot.sessions.length) && (
        <>
          <section className="sidebar-section" aria-label="置顶">
            <h2>置顶</h2>
            {list(groups.pinned, true)}
            {!query.trim() && !groups.pinned.length && (
              <p className="sidebar-empty">常用会话可以置顶到这里</p>
            )}
          </section>
          <section className="sidebar-section" aria-label="项目">
            <div className="sidebar-section-heading">
              <h2>项目</h2>
              <button
                className="icon-button"
                type="button"
                aria-label="新建项目"
                disabled={disabled}
                onClick={(event) => {
                  trigger.current = event.currentTarget;
                  setEditor('new');
                  setName('');
                  setMenuId(null);
                }}
              >
                <Plus size={16} />
              </button>
            </div>
            {editor === 'new' && projectEditor()}
            {groups.projects.map(({ project, sessions }) => {
              const expanded = Boolean(query.trim()) || !collapsed.includes(project.projectId);
              return (
                <div className="sidebar-project" key={project.projectId}>
                  {editor !== null && editor !== 'new' && editor.projectId === project.projectId ? (
                    projectEditor()
                  ) : (
                    <div className="sidebar-project-heading">
                      <button
                        className="project-toggle"
                        type="button"
                        title={project.name}
                        aria-expanded={expanded}
                        onClick={() => {
                          if (!query.trim())
                            remember(
                              expanded
                                ? [...collapsed, project.projectId]
                                : collapsed.filter((id) => id !== project.projectId),
                            );
                        }}
                      >
                        {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        <Folder size={16} />
                        <span>{project.name}</span>
                      </button>
                      <button
                        className="icon-button"
                        type="button"
                        aria-label={`在${project.name}中新建会话`}
                        disabled={disabled}
                        onClick={() => {
                          remember(collapsed.filter((id) => id !== project.projectId));
                          void props.onCreateSession(project.projectId);
                        }}
                      >
                        <Plus size={15} />
                      </button>
                      <button
                        className="icon-button"
                        type="button"
                        aria-label={`${project.name}的项目操作`}
                        aria-expanded={menuId === project.projectId}
                        disabled={disabled}
                        onClick={(event) => {
                          trigger.current = event.currentTarget;
                          setMenuId(menuId === project.projectId ? null : project.projectId);
                        }}
                      >
                        <MoreHorizontal size={16} />
                      </button>
                    </div>
                  )}
                  {menuId === project.projectId && (
                    <div className="sidebar-menu">
                      <button
                        type="button"
                        disabled={disabled}
                        onClick={() => {
                          setEditor(project);
                          setName(project.name);
                          setMenuId(null);
                        }}
                      >
                        重命名项目
                      </button>
                      <button
                        type="button"
                        disabled={disabled}
                        onClick={() => {
                          setDeleting(project);
                          setMenuId(null);
                        }}
                      >
                        删除项目
                      </button>
                      <button type="button" onClick={closeAction}>
                        关闭菜单
                      </button>
                    </div>
                  )}
                  {deleting?.projectId === project.projectId && (
                    <div className="sidebar-delete" role="dialog" aria-label="删除项目">
                      <p>删除项目「{project.name}」？项目内会话将移至未分组，置顶状态保留。</p>
                      <button
                        ref={cancelDelete}
                        type="button"
                        disabled={busy}
                        onClick={closeAction}
                      >
                        取消
                      </button>
                      <button
                        type="button"
                        disabled={disabled}
                        onClick={() => void removeProject()}
                      >
                        确认删除项目
                      </button>
                    </div>
                  )}
                  {expanded && (
                    <div className="project-sessions">
                      {list(sessions)}
                      {!sessions.length && <p className="sidebar-empty">暂无未置顶会话</p>}
                    </div>
                  )}
                </div>
              );
            })}
            {!query.trim() && !snapshot.projects.length && (
              <p className="sidebar-empty">创建项目，整理相关会话</p>
            )}
          </section>
          <section className="sidebar-section" aria-label="未分组会话">
            <h2>未分组会话</h2>
            {list(groups.ungrouped)}
            {!query.trim() && !groups.ungrouped.length && (
              <p className="sidebar-empty">暂无未分组会话</p>
            )}
          </section>
          {noMatches && (
            <p className="sidebar-empty" role="status">
              没有找到会话
            </p>
          )}
        </>
      )}
    </div>
  );
}
