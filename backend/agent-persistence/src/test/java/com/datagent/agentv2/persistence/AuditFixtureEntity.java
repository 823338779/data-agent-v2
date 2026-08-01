package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_session")
final class AuditFixtureEntity extends TenantAuditedEntity {

    /** 测试会话的业务唯一标识。 */
    private String conversationSessionId;

    private String title;
    private String sessionType;
    private String createdByUserId;

    @Version private long version;

    static AuditFixtureEntity session(String id, String title) {
        AuditFixtureEntity value = new AuditFixtureEntity();
        value.setConversationSessionId(id);
        value.title = title;
        value.sessionType = "CHAT";
        value.createdByUserId = "user_admin";
        return value;
    }
}
