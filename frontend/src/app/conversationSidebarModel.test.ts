import { describe, expect, it } from 'vitest';
import { groupSidebar } from './conversationSidebarModel';
import { ConversationSession } from '../domain/agent';
import { ConversationProject } from '../domain/sidebar';

const project: ConversationProject = {
  projectId: 'p1',
  name: '分析',
  version: 0,
  createdAt: '2026-09-15T01:00:00',
  updatedAt: '2026-09-15T01:00:00',
};
const session: ConversationSession = {
  conversationSessionId: 's1',
  title: '每周复盘',
  tag: 'DEBUG',
  version: 0,
  createdAt: '2026-09-15T01:00:00',
  updatedAt: '2026-09-15T01:00:00',
  projectId: 'p1',
  pinnedAt: '2026-09-15T01:00:00Z',
  messages: [],
  execution: null,
};

describe('sidebar grouping', () => {
  it('shows pins only once and restores the project when unpinned', () => {
    const groups = groupSidebar({ projects: [project], sessions: [session] }, '');
    expect(groups.pinned.map((s) => s.conversationSessionId)).toEqual(['s1']);
    expect(groups.projects[0].sessions).toEqual([]);
    expect(groups.ungrouped).toEqual([]);
    expect(
      groupSidebar({ projects: [project], sessions: [{ ...session, pinnedAt: null }] }, '')
        .projects[0].sessions,
    ).toHaveLength(1);
  });
  it('searches tags without exposing unrelated empty projects', () => {
    const groups = groupSidebar({ projects: [project], sessions: [session] }, ' debug ');
    expect(groups.pinned).toHaveLength(1);
    expect(groups.projects).toEqual([]);
    expect(groupSidebar({ projects: [project], sessions: [session] }, 'missing').pinned).toEqual(
      [],
    );
  });
  it('keeps empty projects and uses stable ordering without mutating input', () => {
    const newer = { ...project, projectId: 'p2', createdAt: '2026-09-16T01:00:00' };
    const sessions = [
      { ...session, conversationSessionId: 's1', projectId: null, pinnedAt: null },
      { ...session, conversationSessionId: 's2', projectId: null, pinnedAt: null },
    ];
    const groups = groupSidebar({ projects: [newer, project], sessions }, '');
    expect(groups.projects.map((p) => p.project.projectId)).toEqual(['p1', 'p2']);
    expect(groups.ungrouped.map((s) => s.conversationSessionId)).toEqual(['s2', 's1']);
    expect(sessions[0].conversationSessionId).toBe('s1');
  });
});
