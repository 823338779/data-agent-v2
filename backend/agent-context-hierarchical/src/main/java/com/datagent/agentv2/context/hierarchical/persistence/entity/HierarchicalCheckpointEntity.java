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

/** 持久化会话当前原子发布的 Cold 摘要、指针和 Warm frontier。 */
@Getter
@Setter
@TableName("conversation_context_hierarchical_checkpoint")
public final class HierarchicalCheckpointEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** 检查点业务唯一标识。 */
    private String hierarchicalCheckpointId;

    /** 检查点所属租户。 */
    private String tenantId;

    /** 检查点所属会话。 */
    private String conversationSessionId;

    /** 当前结构化 Cold 摘要 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String coldSummaryDocument;

    /** Cold 摘要覆盖的最大会话消息序号。 */
    private Long summarizedUpToConversationMessageSequence;

    /** Cold 摘要覆盖的完整问答数量。 */
    private Long summarizedCompletedTurnCount;

    /** Cold 摘要的保守 token 估算。 */
    private Integer estimatedTokenCount;

    /** Cold 摘要文档 schema 版本。 */
    private Integer summarySchemaVersion;

    /** Cold 摘要生成提示词版本。 */
    private Integer summaryPromptVersion;

    /** 生成 Cold 摘要的模型提供商。 */
    private String modelProvider;

    /** 生成 Cold 摘要的模型名称。 */
    private String modelName;

    /** 正式发布的 Warm frontier JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String activeFrontierDocument;

    /** 每次发布单调递增的策略代次。 */
    private Long strategyGeneration;

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

    /** checkpoint CAS 使用的乐观锁版本。 */
    @Version private Long version;
}
