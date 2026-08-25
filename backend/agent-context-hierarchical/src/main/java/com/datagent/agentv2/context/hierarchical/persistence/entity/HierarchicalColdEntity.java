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
@TableName("conversation_context_hierarchical_cold")
public final class HierarchicalColdEntity {
    @TableId private Long id;
    private String hierarchicalColdId;
    private String tenantId;
    private String conversationSessionId;
    private Long startTurnSequence;
    private Long endTurnSequence;
    private Long startMessageSequence;
    private Long endMessageSequence;

    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String summaryDocument;

    private Integer persistedTokenCount;
    private Integer renderedTokenCount;
    private Integer targetRenderedTokens;
    private String modelProvider;
    private String modelName;
    private String sourceContentSha256;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
    private boolean deleted;
    @Version private Long version;
}
