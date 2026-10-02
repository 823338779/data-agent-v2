package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
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

    /** 与 type 联合决定前端渲染器，必须为正数。 */
    private Integer schemaVersion;

    /** 已注册的展示变体，默认变体为 default。 */
    private String variant;

    /** 由服务端生成、不得携带可执行代码或样式的 JSON 对象文本。 */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String payload;

    /** 同一消息内从零开始的展示次序。 */
    private Integer displayOrder;

    /** 卡片更新的乐观锁版本，与所属消息版本独立。 */
    @Version private Long version;
}
