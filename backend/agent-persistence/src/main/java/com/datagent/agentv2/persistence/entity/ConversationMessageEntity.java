package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 持久化用户、模型和工具产生的不可变会话消息。 */
@Getter
@Setter
@TableName("conversation_message")
public final class ConversationMessageEntity extends TenantAuditedEntity {

    /** 消息的业务唯一标识，对应数据库 conversation_message_id 列。 */
    private String conversationMessageId;

    /** 消息所属对话会话的业务标识。 */
    private String conversationSessionId;

    /** 产生或消费该消息的 Agent 执行业务标识；没有关联执行时为空。 */
    private String executionId;

    /** 消息角色，例如 user、assistant、system 或 tool。 */
    private String role;

    /** 消息正文；工具消息保存经过清理的 observation。 */
    private String content;

    /** 消息在所属会话内的单调递增业务序号。 */
    private Long conversationMessageSequence;

    /** 用户消息作者的租户用户业务标识；非用户消息为空。 */
    private String authorUserId;

    /** assistant 消息的版本化工具调用文档；其他角色保持为空。 */
    @TableField(jdbcType = JdbcType.OTHER)
    private String toolCalls;

    /** tool observation 对应的内部工具执行业务标识。 */
    private String toolExecutionId;

    /** provider 在当前模型调用内生成的工具调用标识。 */
    private String toolCallId;

    /** tool observation 对应的注册工具名称。 */
    private String toolName;

    /** 消息投影范围，user 对外可见，internal 仅用于模型上下文。 */
    private String visibility;

    /** 乐观锁版本，防止补充执行关联时覆盖并发更新。 */
    @Version private Long version;
}
