package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tenant")
public final class TenantEntity extends AuditedEntity {

    private String name;
    private String status;

    @Version private Long version;
}
