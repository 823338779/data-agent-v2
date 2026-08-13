package com.datagent.agentv2.context.hierarchical.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 持久化带 owner、attempt 和版本 fencing 的可恢复压缩作业。 */
@Getter
@Setter
@TableName("conversation_context_hierarchical_compaction_job")
public final class HierarchicalCompactionJobEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** 压缩作业业务唯一标识。 */
    private String hierarchicalCompactionJobId;

    /** 压缩作业所属租户。 */
    private String tenantId;

    /** 压缩作业所属会话。 */
    private String conversationSessionId;

    /** 创建或恢复作业的 execution 标识。 */
    private String executionId;

    /** 作业 fencing 使用的 execution 尝试序号。 */
    private Long executionAttempt;

    /** 当前有权推动状态的服务实例标识。 */
    private String ownerInstanceId;

    /** 规划时冻结的最高输入消息序号。 */
    private Long inputUpToMessageSequence;

    /** 规划依据的 checkpoint 乐观锁版本。 */
    private Long baseCheckpointVersion;

    /** 规划依据的已发布策略代次。 */
    private Long baseStrategyGeneration;

    /** 不含业务正文的版本化压缩计划 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String planDocument;

    /** GENERATING job 私有持有、供崩溃恢复读取的版本化 Cold 候选 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String candidateColdDocument;

    /** 插件私有作业生命周期状态。 */
    private String status;

    /** 失败时记录的安全稳定错误码。 */
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

    /** 状态转换 CAS 使用的乐观锁版本。 */
    @Version private Long version;
}
