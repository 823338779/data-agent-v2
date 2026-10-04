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

/** 持久化不可变滚动 Cold 的完整审计文档及渲染预算。 */
@Getter
@Setter
@TableName(value = "conversation_context_hierarchical_cold", autoResultMap = true)
public final class HierarchicalColdEntity {
    /** 数据库代理主键，不对外暴露。 */
    @TableId private Long id;

    /** 由会话、摘要正文和模型身份确定性生成的 Cold 业务标识。 */
    private String hierarchicalColdId;

    /** Cold 所属租户。 */
    private String tenantId;

    /** Cold 所属会话。 */
    private String conversationSessionId;

    /** 摘要连续覆盖的首轮序号，最小为 1。 */
    private Long startTurnSequence;

    /** 摘要连续覆盖的末轮序号，不小于首轮。 */
    private Long endTurnSequence;

    /** 摘要连续覆盖的首消息序号，最小为 1。 */
    private Long startMessageSequence;

    /** 摘要连续覆盖的末消息序号，不小于首消息。 */
    private Long endMessageSequence;

    /** 通过 schema 校验的不可变摘要正文；覆盖范围由起止消息序号共同约束。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String summaryDocument;

    /** 摘要文档持久化形态的估算 token 数。 */
    private Integer persistedTokenCount;

    /** 摘要渲染进模型上下文后的估算 token 数。 */
    private Integer renderedTokenCount;

    /** 生成时允许的摘要渲染 token 上限。 */
    private Integer targetRenderedTokens;

    /** 摘要生成模型的提供商标识。 */
    private String modelProvider;

    /** 摘要生成模型名称。 */
    private String modelName;

    /** 直接来源（上一 Cold 与新增原文）内容的 SHA-256，用于审计追溯。 */
    private String sourceContentSha256;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 记录创建者。 */
    private String createdBy;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 记录最后更新者。 */
    private String updatedBy;

    /** 逻辑删除标记。 */
    private boolean deleted;

    /** 乐观锁版本。 */
    @Version private Long version;

    /** 摘要协议版本。 */
    private Integer schemaVersion;

    /** V3 范围与来源摘要 JSON，V2 为空。 */
    private String stepCoverageJson;
}
