package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 项目分组的元数据；删除沿用审计基类的逻辑删除，不删除会话。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("conversation_project")
public final class ConversationProjectEntity extends TenantAuditedEntity {
    private String projectId;
    private String name;

    /** 与会话版本独立，只保护项目重命名和删除。 */
    @Version private Long version;

    /** 为局部更新提供空载体，让框架填充审计时间而不覆盖其他字段。 */
    public static ConversationProjectEntity forUpdate() {
        return new ConversationProjectEntity();
    }
}
