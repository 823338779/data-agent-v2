package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 持久化用户和模型产生的不可变会话消息。 */
@Getter
@Setter
@TableName("conversation_message")
public final class ConversationMessageEntity extends TenantAuditedEntity {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ERROR_UNSUPPORTED_ROLE =
            "conversation message role must be user or assistant";

    /** 消息的业务唯一标识，对应数据库 conversation_message_id 列。 */
    private String conversationMessageId;

    /** 消息所属对话会话的业务标识。 */
    private String conversationSessionId;

    /** 产生或消费该消息的 Agent 执行业务标识；没有关联执行时为空。 */
    private String executionId;

    /** 持久化消息角色，只允许 user 或 assistant */
    private String role;

    public void setRole(String role) {
        if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
            throw new IllegalArgumentException(ERROR_UNSUPPORTED_ROLE);
        }
        this.role = role;
    }

    /** 消息正文；工具消息保存经过清理的 observation。 */
    private String content;

    /** 与消息正文绑定的有序 ProcessItem JSONB 数组。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String thinkingProcess;

    /** 消息在所属会话内的单调递增业务序号。 */
    private Long conversationMessageSequence;

    /** 用户消息作者的租户用户业务标识；非用户消息为空。 */
    private String authorUserId;

    /** 数据库时钟生成的记忆证据创建时间，迁移前记录为空；与可展示的应用审计时间无关。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime memoryEvidenceCreatedAt;

    /** 乐观锁版本，防止补充执行关联时覆盖并发更新。 */
    @Version private Long version;
}
