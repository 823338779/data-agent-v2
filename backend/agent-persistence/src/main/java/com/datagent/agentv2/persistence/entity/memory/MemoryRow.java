package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 记忆表共享的物理行字段；刻意不继承逻辑删除基类，保证正文可真正删除。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class MemoryRow {
    /** 数据库代理主键，仅供持久化层定位。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 当前固定租户，实体工厂显式填写。 */
    private String tenantId;

    /** 当前固定用户，实体工厂显式填写。 */
    private String userId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    protected final void bindCurrentIdentity() {
        tenantId = TenantContext.requiredTenantId();
        userId = OperatorContext.requiredUserId();
    }
}
