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
@TableName(value = "conversation_context_hierarchical_checkpoint", autoResultMap = true)
public final class HierarchicalCheckpointEntity {
    /** 数据库代理主键，不对外暴露。 */
    @TableId private Long id;

    /** checkpoint 业务唯一标识。 */
    private String hierarchicalCheckpointId;

    /** checkpoint 所属租户。 */
    private String tenantId;

    /** checkpoint 所属会话，每个会话最多一条有效记录。 */
    private String conversationSessionId;

    /** 当前发布的 Cold 标识；尚未压缩历史时为空。 */
    private String activeColdId;

    /** 策略已评估到的最高消息序号。 */
    private Long evaluatedUpToConversationMessageSequence;

    /** 每次成功发布后单调递增的策略代次。 */
    private Long strategyGeneration;

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

    /** checkpoint CAS 发布使用的乐观锁版本。 */
    @Version private Long version;
}
