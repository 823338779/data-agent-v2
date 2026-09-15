import { ConversationSession } from './agent';

/** 一级会话分组，只保存组织元数据。 */
export interface ConversationProject {
  projectId: string;
  name: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 同一读取快照中的全部项目和会话摘要。 */
export interface ConversationSidebarSnapshot {
  projects: ConversationProject[];
  sessions: ConversationSession[];
}

export interface SetConversationOrganization {
  /** null 表示移出项目，必须显式提交。 */
  projectId: string | null;
  pinned: boolean;
  expectedVersion: number;
}
