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

/** 持久化 execution 首次进入 hierarchical 策略时冻结的策略快照。 */
@Getter
@Setter
@TableName("conversation_context_hierarchical_execution_policy")
public final class HierarchicalExecutionPolicyEntity {

    /** 数据库代理主键，仅用于持久化层内部定位。 */
    @TableId private Long id;

    /** 冻结策略记录的业务唯一标识。 */
    private String hierarchicalExecutionPolicyId;

    /** 策略记录所属租户。 */
    private String tenantId;

    /** 策略记录所属会话。 */
    private String conversationSessionId;

    /** 首次使用该策略的 execution 标识。 */
    private String executionId;

    /** 不可变策略快照 JSON 文档。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String policyDocument;

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
