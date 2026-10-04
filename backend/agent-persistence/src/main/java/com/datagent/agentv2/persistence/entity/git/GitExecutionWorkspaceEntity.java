package com.datagent.agentv2.persistence.entity.git;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** 不可变执行目标的持久记录；仅在受理事务首次创建。 */
@Getter
@Setter
@TableName("git_execution_workspace")
public final class GitExecutionWorkspaceEntity extends TenantAuditedEntity {
    /** 受理执行的唯一身份，不能重新绑定。 */
    private String executionId;

    /** 受理时会话，仅用于审计和重放核对。 */
    private String conversationSessionId;

    /** 受理时固定的工作区标识。 */
    private String workspaceId;

    /** 固定受控 dbt 项目标识。 */
    private String projectId;

    /** 固定 dbt 分支实例身份。 */
    private String branchId;

    /** 固定完整远端引用，工具参数不能覆盖。 */
    private String gitRef;
}
