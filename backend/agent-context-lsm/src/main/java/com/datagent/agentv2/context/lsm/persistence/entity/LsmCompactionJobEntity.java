package com.datagent.agentv2.context.lsm.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 持久化可恢复的 LSM 压缩计划及其执行归属。 */
@Getter
@Setter
@TableName("conversation_context_lsm_job")
public final class LsmCompactionJobEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** 压缩作业的业务唯一标识。 */
    private String compactionJobId;

    /** 作业所属租户。 */
    private String tenantId;

    /** 作业所属会话。 */
    private String conversationSessionId;

    /** 触发或恢复该作业的执行标识。 */
    private String executionId;

    /** 作业受 fencing 保护的执行尝试序号。 */
    private Long executionAttempt;

    /** 规划时冻结的最高输入消息序号。 */
    private Long inputUpToMessageSequence;

    /** 规划读取到的 manifest 代次。 */
    private Long sourceManifestGeneration;

    /** 规划读取到的 manifest 乐观锁版本。 */
    private Long sourceManifestVersion;

    /** 规划时冻结的策略快照 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String policySnapshot;

    /** 不含正文的压缩计划 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String planDocument;

    /** 规范化压缩计划 JSON 的 SHA-256 指纹。 */
    private String planSha256;

    /** 作业状态，例如 PLANNED、GENERATING、READY 或 PUBLISHED。 */
    private String status;

    /** 当前拥有该作业的服务实例。 */
    private String ownerInstanceId;

    /** 失败时记录的稳定失败代码。 */
    private String failureCode;

    /** 作业进入终态的时间。 */
    private LocalDateTime completedAt;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 创建记录的插件操作者标识。 */
    private String createdBy;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 最后更新记录的插件操作者标识。 */
    private String updatedBy;

    /** 逻辑删除标记。 */
    private boolean deleted;

    /** 乐观锁版本。 */
    @Version private Long version;
}
