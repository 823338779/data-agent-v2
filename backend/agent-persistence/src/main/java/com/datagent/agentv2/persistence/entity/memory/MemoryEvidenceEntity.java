package com.datagent.agentv2.persistence.entity.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 记忆与证据的稳定关联；不复制可删除聊天正文。 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@TableName("personal_memory_evidence")
public final class MemoryEvidenceEntity extends MemoryRow {
    /** 首次关联的实际模型提示词版本；人工操作及未提供版本的旧调用为空。 */
    private String promptVersion;

    private String memoryId;
    private String evidenceId;
    private String kind;
    private String conversationId;
    private String executionId;
    private Long sequence;
    private String authorUserId;
    private Long messageVersion;
    private LocalDateTime evidenceCreatedAt;

    public static MemoryEvidenceEntity create() {
        var entity = new MemoryEvidenceEntity();
        entity.bindCurrentIdentity();
        return entity;
    }
}
