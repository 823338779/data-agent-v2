package com.datagent.agentv2.persistence.entity.artifact;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** Markdown 占位符对应的版本化受控 UI Block 持久化实体。 */
@Getter
@Setter
@TableName(value = "message_ui_block", autoResultMap = true)
public final class MessageUiBlockEntity extends TenantAuditedEntity {

    private String uiBlockId;
    private String conversationMessageId;
    private String type;
    private Integer schemaVersion;
    private String variant;

    /** 由服务端生成、不得携带可执行代码或样式的 JSON 对象文本。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String payload;

    private Integer displayOrder;

    @Version private Long version;
}
