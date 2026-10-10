package com.datagent.agentv2.persistence.entity.development;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** DevelopmentProject 的持久状态；数据库操作仅由 persistence 服务负责。 */
@Getter
@Setter
@TableName("development_project")
public final class DevelopmentProjectEntity extends TenantAuditedEntity {
    /** Agent 项目身份。 */
    private String projectId;

    /** 固定受控仓库，不含凭据。 */
    private String repository;

    /** 当前环境配置 JSON，不含文件正文或凭据。 */
    private String configJson;

    /** 配置乐观锁版本。 */
    private Long version;

    /** 项目是否允许新开发操作。 */
    private Boolean enabled;

    /** 旧多仓库或来源不全的迁移诊断。 */
    private String diagnostic;
}
