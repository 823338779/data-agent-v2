import { ConversationSession } from '../domain/agent';
import { ConversationProject, ConversationSidebarSnapshot } from '../domain/sidebar';

export interface SidebarGroups {
  pinned: ConversationSession[];
  projects: { project: ConversationProject; sessions: ConversationSession[] }[];
  ungrouped: ConversationSession[];
}

/** 从唯一的平铺快照派生显示分区，置顶不改变归属，也不在项目下重复出现。 */
export function groupSidebar(snapshot: ConversationSidebarSnapshot, query: string): SidebarGroups {
  const needle = query.trim().toLowerCase();
  const visible = snapshot.sessions.filter((s) =>
    `${s.title} ${s.tag}`.toLowerCase().includes(needle),
  );
  const pinned = visible
    .filter((s) => s.pinnedAt !== null)
    .sort(
      (a, b) =>
        Date.parse(b.pinnedAt!) - Date.parse(a.pinnedAt!) ||
        b.conversationSessionId.localeCompare(a.conversationSessionId),
    );
  const byProject = new Map<string, ConversationSession[]>();
  const ungrouped: ConversationSession[] = [];
  for (const session of visible) {
    if (session.pinnedAt !== null) continue;
    if (session.projectId === null) ungrouped.push(session);
    else {
      const members = byProject.get(session.projectId) ?? [];
      members.push(session);
      byProject.set(session.projectId, members);
    }
  }
  const recentFirst = (a: ConversationSession, b: ConversationSession) =>
    b.updatedAt.localeCompare(a.updatedAt) ||
    b.conversationSessionId.localeCompare(a.conversationSessionId);
  return {
    pinned,
    projects: [...snapshot.projects]
      .sort(
        (a, b) => a.createdAt.localeCompare(b.createdAt) || a.projectId.localeCompare(b.projectId),
      )
      .map((project) => ({
        project,
        sessions: (byProject.get(project.projectId) ?? []).sort(recentFirst),
      }))
      .filter((group) => !needle || group.sessions.length > 0),
    ungrouped: ungrouped.sort(recentFirst),
  };
}
