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

/** 持久化 Warm 层不可变二进制摘要节点。 */
@Getter
@Setter
@TableName("conversation_context_hierarchical_summary_node")
public final class HierarchicalSummaryNodeEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** 摘要节点业务唯一标识。 */
    private String hierarchicalSummaryNodeId;

    /** 摘要节点所属租户。 */
    private String tenantId;

    /** 摘要节点所属会话。 */
    private String conversationSessionId;

    /** 二进制摘要树层级。 */
    private Integer level;

    /** 节点覆盖的完整问答数量。 */
    private Long coveredCompletedTurnCount;

    /** 节点覆盖的首个会话消息序号。 */
    private Long startConversationMessageSequence;

    /** 节点覆盖的最后一个会话消息序号。 */
    private Long endConversationMessageSequence;

    /** 非叶节点左侧直接子节点业务标识。 */
    private String leftChildNodeId;

    /** 非叶节点右侧直接子节点业务标识。 */
    private String rightChildNodeId;

    /** 经版本校验的摘要正文 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String summaryDocument;

    /** 摘要正文的保守 token 估算。 */
    private Integer estimatedTokenCount;

    /** 摘要正文 schema 版本。 */
    private Integer summarySchemaVersion;

    /** 摘要生成提示词版本。 */
    private Integer summaryPromptVersion;

    /** 生成摘要的模型提供商。 */
    private String modelProvider;

    /** 生成摘要的模型名称。 */
    private String modelName;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 创建记录的插件操作者标识。 */
    private String createdBy;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 最后更新记录的插件操作者标识。 */
    private String updatedBy;

    /** 逻辑删除标记，不可变节点正常情况下不删除。 */
    private boolean deleted;

    /** 持久化记录的乐观锁版本。 */
    @Version private Long version;
}
