package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_session")
final class AuditFixtureEntity extends TenantAuditedEntity {

    private String title;
    private String sessionType;
    private String createdBy;

    @Version private long version;

    static AuditFixtureEntity session(String id, String title) {
        AuditFixtureEntity value = new AuditFixtureEntity();
        value.setUuid(id);
        value.title = title;
        value.sessionType = "CHAT";
        value.createdBy = "user_admin";
        return value;
    }
}
