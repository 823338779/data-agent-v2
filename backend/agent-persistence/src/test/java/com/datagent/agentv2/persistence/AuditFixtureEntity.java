package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_session")
final class AuditFixtureEntity extends TenantAuditedEntity {

    @TableId private String id;

    private String title;
    private String sessionType;
    private String createdBy;

    @Version private long version;

    static AuditFixtureEntity session(String id, String title) {
        AuditFixtureEntity value = new AuditFixtureEntity();
        value.id = id;
        value.title = title;
        value.sessionType = "CHAT";
        value.createdBy = "user_admin";
        return value;
    }
}
