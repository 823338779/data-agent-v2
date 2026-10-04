package com.datagent.agentv2.persistence.entity.git;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 会话与工作区关联，生命周期独立于会话。 */
@Getter
@Setter
@TableName("git_session_workspace")
public final class GitSessionWorkspaceEntity extends TenantAuditedEntity {
    /** 关联会话标识，不表示工作区独占所有权。 */
    private String conversationSessionId;

    /** 独立工作区业务标识。 */
    private String workspaceId;
}
