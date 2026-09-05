package com.datagent.agentv2.persistence.entity.artifact;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/** assistant 消息与正式 Artifact 的有序持久化关系。 */
@Getter
@Setter
@TableName("conversation_message_artifact")
public final class ConversationMessageArtifactEntity extends TenantAuditedEntity {

    private String conversationMessageArtifactId;
    private String conversationMessageId;
    private String artifactId;
    private Integer displayOrder;

    @Version private Long version;
}
