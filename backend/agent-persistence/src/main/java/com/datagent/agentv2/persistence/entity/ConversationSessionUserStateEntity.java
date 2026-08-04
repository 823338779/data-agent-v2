package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 保存单个用户对一个可见会话的已读游标和稳定排序位置。 */
@Getter
@Setter
@TableName("conversation_session_user_state")
public final class ConversationSessionUserStateEntity {

    /** 数据库自增主键，仅用于持久化层内部定位用户会话状态记录。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 状态所属租户，用于和用户、会话共同形成隔离边界。 */
    private String tenantId;

    /** 拥有该独立状态的用户业务标识。 */
    private String userId;

    /** 用户可见的会话业务标识。 */
    private String conversationSessionId;

    /** 用户已确认展示的最大可通知执行事件序号。 */
    private Long lastReadEventSequence;

    /** 用户会话列表中的连续排序位置，数值越小越靠前。 */
    private Long sortOrder;

    /** 用户状态首次建立的时间。 */
    private Instant createdAt;

    /** 已读游标或排序最后变化的时间。 */
    private Instant updatedAt;

    /** 状态行乐观锁版本，防止并发更新相互覆盖。 */
    @Version private Long version;
}
