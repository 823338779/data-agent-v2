package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

@Getter
@Setter
@TableName("agent_message")
public final class AgentMessageEntity extends TenantAuditedEntity {

    /** 消息的业务唯一标识，对应数据库 message_id 列。 */
    private String messageId;

    private String sessionId;
    private String runId;
    private String role;
    private String content;
    private Long sequence;
    private String authorUserId;

    /** assistant 消息的版本化工具调用文档；其他角色保持为空。 */
    @TableField(jdbcType = JdbcType.OTHER)
    private String toolCalls;

    /** tool Observation 对应的内部执行标识。 */
    private String toolExecutionId;

    /** provider 在当前 Model Turn 内生成的工具调用标识。 */
    private String toolCallId;

    /** tool Observation 对应的注册工具名称。 */
    private String toolName;

    /** 消息投影范围，user 对外可见，internal 仅用于模型上下文。 */
    private String visibility;

    @Version private Long version;
}
