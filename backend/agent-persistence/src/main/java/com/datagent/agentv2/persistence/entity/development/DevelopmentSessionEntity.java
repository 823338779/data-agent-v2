package com.datagent.agentv2.persistence.entity.development;

import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** DevelopmentSession 的持久状态；数据库操作仅由 persistence 服务负责。 */
@Getter
@Setter
@TableName("development_session")
public final class DevelopmentSessionEntity extends TenantAuditedEntity {
    /** 固定开发会话身份。 */
    private String sessionId;

    /** 创建时 Agent 项目，删除项目仍保留。 */
    private String projectId;

    /** 冻结仓库地址。 */
    private String repository;

    /** 冻结 Git 短分支名。 */
    private String branchName;

    /** 创建时远端完整 SHA。 */
    private String baselineSha;

    /** 绑定时配置快照 JSON。 */
    private String configSnapshot;

    /** 受控临时 clone 的服务端随机键。 */
    private String storageKey;

    /** 本地编辑修订号。 */
    private Long revision;

    /** CAS 状态版本。 */
    private Long version;

    /** 本地源码状态，不表示 Git 保存或部署。 */
    private String localState;

    /** 最近已核实的完整本地文件摘要。 */
    private String localDigest;

    /** 最近已核实推送的 SHA。 */
    private String pushedSha;

    /** 显式选定的分析构建，空时首次解析部署。 */
    private String selectedBuildId;
}
