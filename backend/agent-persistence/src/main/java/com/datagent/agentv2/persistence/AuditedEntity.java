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

    @TableField("deleted_at")
    private Instant deletedAt;

    @TableLogic(value = "false", delval = "true, deleted_at = CURRENT_TIMESTAMP")
    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    private boolean deleted;
}
