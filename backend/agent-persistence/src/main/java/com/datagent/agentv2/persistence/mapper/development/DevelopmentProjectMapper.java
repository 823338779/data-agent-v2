package com.datagent.agentv2.persistence.mapper.development;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.development.DevelopmentProjectEntity;
import org.apache.ibatis.annotations.Mapper;

/** 复用 MyBatis-Plus 单表持久能力。 */
@Mapper
public interface DevelopmentProjectMapper extends BaseMapper<DevelopmentProjectEntity> {
    /** 多表历史关联必须在数据库内完成；不能把旧 dbt project_id 当作 Agent 项目。 */
    @org.apache.ibatis.annotations.Select(
            """
        SELECT DISTINCT ON (s.conversation_session_id, w.workspace_id)
               s.project_id, s.conversation_session_id AS session_id, w.workspace_id,
               w.repository_id, w.git_ref, c.commit_id AS commit_sha
        FROM conversation_session s
        JOIN git_session_workspace a ON a.conversation_session_id=s.conversation_session_id AND a.tenant_id=s.tenant_id
        JOIN git_workspace w ON w.workspace_id=a.workspace_id AND w.tenant_id=a.tenant_id
        JOIN git_change c ON c.workspace_id=w.workspace_id AND c.tenant_id=w.tenant_id
        WHERE s.project_id=#{projectId} AND c.status='PUSHED' AND c.commit_id IS NOT NULL
              AND a.is_deleted=false
        ORDER BY s.conversation_session_id, w.workspace_id, c.created_at DESC, c.id DESC
        """)
    java.util.List<com.datagent.agentv2.persistence.entity.development.LegacyDevelopmentSource>
            legacySources(@org.apache.ibatis.annotations.Param("projectId") String projectId);
}
