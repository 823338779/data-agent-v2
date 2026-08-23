package com.datagent.agentv2.context.hierarchical.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 持久化当前滚动 Cold 引用及其发布 fence。 */
@Getter
@Setter
@TableName("conversation_context_hierarchical_checkpoint")
public final class HierarchicalCheckpointEntity {
    @TableId private Long id;
    private String hierarchicalCheckpointId;
    private String tenantId;
    private String conversationSessionId;
    private String activeColdId;
    private Long evaluatedUpToConversationMessageSequence;
    private Long strategyGeneration;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
    private boolean deleted;
    @Version private Long version;
}
