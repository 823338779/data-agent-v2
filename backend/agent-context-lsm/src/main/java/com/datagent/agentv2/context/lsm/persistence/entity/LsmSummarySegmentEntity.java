package com.datagent.agentv2.context.lsm.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.SegmentKind;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 持久化不可变的 LSM 摘要段及其来源、模型和策略追溯信息。 */
@Getter
@Setter
@TableName("conversation_context_lsm_segment")
public final class LsmSummarySegmentEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** 摘要段的业务唯一标识。 */
    private String summarySegmentId;

    /** 摘要段所属租户。 */
    private String tenantId;

    /** 摘要段所属会话。 */
    private String conversationSessionId;

    /** 按摘要输入确定性计算的段幂等键。 */
    private String segmentKey;

    /** 段类型；MyBatis 按枚举名写入数据库中的 STANDARD 或 ROOT。 */
    private SegmentKind kind;

    /** 段所在 LSM 层级。 */
    private Integer level;

    /** 段覆盖的首个完整轮次序号。 */
    private Long startTurnSequence;

    /** 段覆盖的最后一个完整轮次序号。 */
    private Long endTurnSequence;

    /** 段覆盖的首个消息序号。 */
    private Long startMessageSequence;

    /** 段覆盖的最后一个消息序号。 */
    private Long endMessageSequence;

    /** 是否包含尚未完成的部分轮次。 */
    private boolean partialSegment;

    /** 经 schema 校验后的摘要正文 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String summaryDocument;

    /** 摘要正文的估算 token 数。 */
    private Integer estimatedTokenCount;

    /** 生成该段的来源段业务标识 JSON 数组。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String sourceSegmentIds;

    /** 来源正文的 SHA-256 指纹。 */
    private String sourceContentSha256;

    /** 摘要正文 JSON schema 版本。 */
    private Integer summarySchemaVersion;

    /** 生成摘要时使用的提示词版本。 */
    private Integer promptVersion;

    /** 生成摘要的模型提供商。 */
    private String modelProvider;

    /** 生成摘要的模型名称。 */
    private String modelName;

    /** 段创建时冻结的策略快照 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String creationPolicy;

    /** 构成该摘要段的来源段数量。 */
    private Integer sourceSegmentCount;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 创建记录的插件操作者标识。 */
    private String createdBy;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 最后更新记录的插件操作者标识。 */
    private String updatedBy;

    /** 逻辑删除标记，摘要段正常情况下不会被删除。 */
    private boolean deleted;

    /** 乐观锁版本，用于持久化层并发保护。 */
    @Version private Long version;
}
