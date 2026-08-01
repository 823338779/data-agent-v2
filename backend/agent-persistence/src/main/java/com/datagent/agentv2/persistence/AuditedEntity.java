package com.datagent.agentv2.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class AuditedEntity {

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField(value = "deleted_at", fill = FieldFill.UPDATE)
    private Instant deletedAt;

    @TableLogic(value = "false", delval = "true")
    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    private boolean deleted;
}
